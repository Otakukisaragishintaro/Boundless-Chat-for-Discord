package io.github.otakukisaragishintaro.boundlesschatdiscord.translate;

import java.util.concurrent.ConcurrentHashMap;

import io.github.otakukisaragishintaro.boundlesschatdiscord.core.BlankLines;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Config;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.DiscordTokenMarkers;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Http;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Protocols;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.ProjectLog;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Scheduler;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Segments;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.TranslationException;

/**
 * JSON 翻译引擎（需求 §5.5）。
 *
 * <p>按配置的供应商协议工作，不绑定任何特定服务商：
 * <ol>
 *   <li>用 {@link Protocols#requestBody} 构造请求体（协议决定格式）。</li>
 *   <li>用 {@link Http} 发送 POST，超时可配；端点由 {@link Protocols#endpoint} 按协议解析。</li>
 *   <li>由 {@link Protocols#createDecoder} 为本次响应选择唯一译文解码器。</li>
 * </ol>
 *
 * <p>默认协议为 OpenAI 兼容 {@code /chat/completions}
 * （见 {@link Config#DEF_PROTOCOL}）。</p>
 *
 * <p>不承诺兼容所有接口：配置不完整或响应结构不匹配时通过
 * {@link TranslationException} 给出明确错误（需求 §5.5）。</p>
 */
public final class JsonApiEngine implements Scheduler.Engine {

    private final Config.Snapshot cfg;
    private final Http http;
    private final ConcurrentHashMap<Thread, Http.Request> active = new ConcurrentHashMap<>();

    public JsonApiEngine(Config.Snapshot cfg) {
        this(cfg, new Http(cfg.timeoutMs));
    }

    JsonApiEngine(Config.Snapshot cfg, Http http) {
        this.cfg = cfg;
        this.http = http;
    }

    /** Non-2xx HTTP failures retain the exact provider body for settings-test display. */
    static final class UpstreamHttpException extends TranslationException {
        final String responseBody;

        UpstreamHttpException(Kind kind, Reason reason, String message, int httpStatus,
                              String responseBody) {
            super(kind, reason, message, httpStatus, null);
            this.responseBody = responseBody;
        }
    }

    /** Decoded exchange metadata stays with the single request owner, including late cancellation. */
    /** Carries bounded failure context to the settings test without changing error classification. */
    static final class DiagnosticException extends TranslationException {
        final String stage;
        final String requestPreview;
        final String translatedPreview;

        DiagnosticException(TranslationException original, String stage,
                            String requestText, String translatedText) {
            super(original.kind(), original.reason(), original.getMessage(),
                    original.httpStatus(), original);
            this.stage = stage;
            this.requestPreview = safeDiagnosticPreview(requestText, 1000);
            this.translatedPreview = safeDiagnosticPreview(translatedText, 1000);
        }
    }

    static final class Exchange {
        final String translated;
        final int httpStatus;

        Exchange(String translated, int httpStatus) {
            this.translated = translated;
            this.httpStatus = httpStatus;
        }
    }

    /**
     * Keep a Discord message as one scheduled/cached translation, but optionally
     * send long multi-paragraph bodies as smaller requests. Only separators between
     * independently requested chunks are copied from the source; paragraph layout
     * inside each request may be freely normalized by the translation provider.
     */
    @Override
    public String translate(String text) throws Exception {
        return translateInternal(text, true);
    }

    /** Same production pipeline for the settings test, with details returned to UI, not logcat. */
    String translateForTest(String text) throws Exception {
        return translateInternal(text, false);
    }

    private String translateInternal(String text, boolean logFailureDiagnostics) throws Exception {
        String previousRequestId = ProjectLog.beginRequest();
        try {
            return translateWithRequestContext(text, logFailureDiagnostics);
        } finally {
            ProjectLog.endRequest(previousRequestId);
        }
    }

    private String translateWithRequestContext(String text, boolean logFailureDiagnostics) throws Exception {
        // Literal Discord syntax can also arrive inside ordinary text nodes or primitive
        // StructurableText. Shield it before every provider request and restore it only
        // after the same retained-marker integrity check used for native opaque nodes.
        DiscordTokenMarkers.Protection protection = DiscordTokenMarkers.protect(text,
                cfg.protectMentions, cfg.protectChannelsEmojis, cfg.protectUrls);
        String requestText = protection.text;
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        java.util.List<String> separators = new java.util.ArrayList<>();
        if (!cfg.experimentalLongMessageChunking || requestText == null
                || requestText.length() <= cfg.experimentalLongMessageThreshold) {
            return protection.restore(execute(requestText, true, logFailureDiagnostics,
                    !logFailureDiagnostics).translated);
        }
        splitParagraphs(requestText, paragraphs, separators);

        if (paragraphs.size() <= 1) {
            ProjectLog.info("translation.chunking skipped reason=no_blank_line_boundary"
                    + " sourceChars=" + requestText.length());
            return protection.restore(execute(requestText, true, logFailureDiagnostics,
                    !logFailureDiagnostics).translated);
        }

        ParagraphGroups groups = groupParagraphs(paragraphs, separators,
                cfg.experimentalLongMessageThreshold);
        if (groups.chunks.size() <= 1) {
            ProjectLog.info("translation.chunking skipped reason=paragraphs_fit_one_request"
                    + " sourceChars=" + requestText.length()
                    + " paragraphs=" + paragraphs.size());
            return protection.restore(execute(requestText, true, logFailureDiagnostics,
                    !logFailureDiagnostics).translated);
        }

        StringBuilder combined = new StringBuilder(requestText.length());
        ProjectLog.info("translation.chunking start sourceChars=" + requestText.length()
                + " threshold=" + cfg.experimentalLongMessageThreshold
                + " paragraphs=" + paragraphs.size()
                + " requests=" + groups.chunks.size());
        for (int i = 0; i < groups.chunks.size(); i++) {
            String group = groups.chunks.get(i);
            ProjectLog.info("translation.chunking request index=" + (i + 1)
                    + " sourceChars=" + group.length()
                    + " paragraphsGrouped=" + groups.paragraphCounts.get(i));
            try {
                String translated = execute(group, true, logFailureDiagnostics,
                        !logFailureDiagnostics).translated;
                combined.append(translated);
            } catch (Exception failure) {
                ProjectLog.warn("translation.chunking failed index=" + (i + 1)
                        + " errorClass=" + failure.getClass().getSimpleName()
                        + " errorKind=" + (failure instanceof TranslationException
                                ? ((TranslationException) failure).kind().name() : "none")
                        + " errorReason=" + (failure instanceof TranslationException
                                ? ((TranslationException) failure).reason().name() : "UNKNOWN_FAILURE")
                        + " httpStatus=" + (failure instanceof TranslationException
                                ? ((TranslationException) failure).httpStatus() : 0));
                throw failure;
            }
            if (i < groups.separators.size()) combined.append(groups.separators.get(i));
        }
        ProjectLog.info("translation.chunking complete chunks=" + groups.chunks.size()
                + " protectedDiscordTokens=" + protection.tokenCount());
        return protection.restore(combined.toString());
    }

    /** Split using the same whitespace-aware blank-line rules as Discord node alignment. */
    static void splitParagraphs(String source, java.util.List<String> paragraphs,
                                java.util.List<String> separators) {
        BlankLines.Split split = BlankLines.split(source);
        paragraphs.addAll(split.chunks);
        separators.addAll(split.separators);
    }

    /** A request-sized group of whole paragraphs plus the exact separators between requests. */
    static final class ParagraphGroups {
        final java.util.List<String> chunks;
        final java.util.List<String> separators;
        final java.util.List<Integer> paragraphCounts;

        ParagraphGroups(java.util.List<String> chunks, java.util.List<String> separators,
                        java.util.List<Integer> paragraphCounts) {
            this.chunks = chunks;
            this.separators = separators;
            this.paragraphCounts = paragraphCounts;
        }
    }

    /**
     * Groups only complete paragraphs. A final undersized group is folded into its
     * predecessor so a short URL/code/formatting tail is never sent without context.
     * No character-level splitting is performed, even when one paragraph exceeds threshold.
     */
    static ParagraphGroups groupParagraphs(java.util.List<String> paragraphs,
                                           java.util.List<String> separators, int threshold) {
        java.util.List<String> chunks = new java.util.ArrayList<>();
        java.util.List<String> boundaries = new java.util.ArrayList<>();
        java.util.List<Integer> counts = new java.util.ArrayList<>();
        if (paragraphs == null || paragraphs.isEmpty()) {
            return new ParagraphGroups(chunks, boundaries, counts);
        }
        if (separators == null || separators.size() != paragraphs.size() - 1) {
            throw new IllegalArgumentException("Paragraph separators do not match paragraph count");
        }

        StringBuilder current = new StringBuilder();
        int currentCount = 0;
        int minimumIndependentGroup = Math.max(1, threshold / 2);
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) {
                String boundary = separators.get(i - 1);
                String nextParagraph = paragraphs.get(i);
                int candidateLength = current.length() + boundary.length() + nextParagraph.length();
                // Do not strand a tiny leading paragraph. Split only after the current
                // group is substantial and adding the next whole paragraph exceeds target.
                if (current.length() >= minimumIndependentGroup
                        && candidateLength > Math.max(1, threshold)) {
                    chunks.add(current.toString());
                    counts.add(currentCount);
                    boundaries.add(boundary);
                    current.setLength(0);
                    currentCount = 0;
                } else {
                    current.append(boundary);
                }
            }
            current.append(paragraphs.get(i));
            currentCount++;
        }
        chunks.add(current.toString());
        counts.add(currentCount);

        // A short trailing paragraph is more likely to be a URL, code fragment, or
        // continuation than an independent translation unit. Keep it with prior context.
        if (chunks.size() > 1
                && chunks.get(chunks.size() - 1).codePointCount(
                        0, chunks.get(chunks.size() - 1).length()) < minimumIndependentGroup) {
            int last = chunks.size() - 1;
            String merged = chunks.get(last - 1) + boundaries.get(last - 1) + chunks.get(last);
            chunks.set(last - 1, merged);
            counts.set(last - 1, counts.get(last - 1) + counts.get(last));
            chunks.remove(last);
            counts.remove(last);
            boundaries.remove(last - 1);
        }
        return new ParagraphGroups(chunks, boundaries, counts);
    }

    /** Connection probes share all request/decoder ownership, but do not judge translation safety. */
    Exchange execute(String text, boolean validateTranslation) throws Exception {
        // Connection probes keep the redacted diagnostic contract and do not capture message previews.
        return execute(text, validateTranslation, false, false);
    }

    private Exchange execute(String text, boolean validateTranslation,
                             boolean logFailureDiagnostics, boolean captureFailureDiagnostics)
            throws Exception {
        Thread worker = Thread.currentThread();
        Http.Request request = new Http.Request();
        if (active.putIfAbsent(worker, request) != null) {
            throw new IllegalStateException("Worker already owns a request");
        }
        String failureStage = "request_setup";
        String diagnosticTranslated = null;
        try {
            // Registration precedes validation/body construction. A prior cancel interrupts
            // the worker; a concurrent cancel marks this handle before any connection exists.
            request.checkCancelled();
            if (text == null || text.trim().isEmpty()) {
                throw new TranslationException(TranslationException.Kind.EMPTY, "原文为空，无需翻译");
            }
            Http.Headers headers = validateConfig(cfg);
            failureStage = "http_response_decode";
            Http.Response response = requestOnce(text, request, headers);
            failureStage = "translation_validation";
            diagnosticTranslated = response.body;
            String translated = response.body;
            if (translated == null || translated.trim().isEmpty()) {
                throw new TranslationException(TranslationException.Kind.EMPTY,
                        "提取到的译文为空", response.status, null);
            }
            translated = translated.trim();
            if (validateTranslation) {
                if (cfg.stripOuterWrapper) {
                    translated = Segments.stripOuterResponseWrapper(translated, text);
                }
                String violation = Segments.plainTextViolation(translated, text,
                        cfg.checkWrapper, cfg.checkPlaceholders,
                        cfg.checkRetainedMarkers, cfg.checkLength,
                        cfg.minLengthPercent, cfg.maxLengthPercent);
                if (violation != null) {
                    String safeViolation = violation.replaceAll("（[0-9]+/[0-9]+）", "");
                    throw new TranslationException(TranslationException.Kind.PARSE,
                            TranslationException.Reason.TRANSLATION_INVALID,
                            "译文未通过防损坏校验：" + safeViolation, response.status, null);
                }
            }
            request.checkCancelled();
            return new Exchange(translated, response.status);
        } catch (Exception e) {
            request.checkCancelled();
            String kind = e instanceof TranslationException
                    ? ((TranslationException) e).kind().name() : "UNKNOWN";
            String reason = e instanceof TranslationException
                    ? ((TranslationException) e).reason().name() : "UNKNOWN_FAILURE";
            int status = e instanceof TranslationException
                    ? ((TranslationException) e).httpStatus() : 0;
            String detail = e instanceof TranslationException
                    ? safeDiagnosticDetail(e.getMessage()) : e.getClass().getSimpleName();
            if (logFailureDiagnostics) {
                ProjectLog.warn("translation.failure stage=" + failureStage
                        + " errorClass=" + e.getClass().getSimpleName()
                        + " errorKind=" + kind
                        + " errorReason=" + reason
                        + " httpStatus=" + status
                        + " sourceChars=" + codePointLength(text)
                        + " sourcePreview=" + safeDiagnosticPreview(text, 200)
                        + " sourceTail=" + safeDiagnosticTail(text, 100)
                        + " translatedChars=" + codePointLength(diagnosticTranslated)
                        + " translatedPreview=" + safeDiagnosticPreview(diagnosticTranslated, 200)
                        + " detail=" + detail);
            }
            if (captureFailureDiagnostics && !(e instanceof DiagnosticException)) {
                TranslationException classified = e instanceof TranslationException
                        ? (TranslationException) e
                        : new TranslationException(TranslationException.Kind.UNKNOWN,
                                TranslationException.Reason.UNKNOWN_FAILURE,
                                e.getClass().getSimpleName(), 0, e);
                throw new DiagnosticException(classified, failureStage, text, diagnosticTranslated);
            }
            throw e;
        } finally {
            active.remove(worker, request);
        }
    }

    /** Keep diagnostic details useful while preventing multiline or unbounded log output. */
    private static String safeDiagnosticDetail(String message) {
        if (message == null || message.isEmpty()) return "none";
        String safe = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return safe.length() <= 4096 ? safe : safe.substring(0, 4096);
    }

    /** Counts Unicode code points so preview limits do not split surrogate pairs. */
    private static int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /** Escapes control characters and bounds diagnostic previews; never logs an entire message. */
    private static String safeDiagnosticPreview(String value, int maxCodePoints) {
        if (value == null) return "none";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < value.length() && count < maxCodePoints;) {
            int point = value.codePointAt(i);
            i += Character.charCount(point);
            count++;
            if (point == '\r') out.append("\\r");
            else if (point == '\n') out.append("\\n");
            else if (point == '\t') out.append("\\t");
            else if (Character.isISOControl(point)) out.append('?');
            else out.appendCodePoint(point);
        }
        if (codePointLength(value) > maxCodePoints) out.append("…");
        return out.toString();
    }

    /** Keeps the final context of long messages, also with escaped control characters. */
    private static String safeDiagnosticTail(String value, int maxCodePoints) {
        if (value == null) return "none";
        int count = codePointLength(value);
        int start = count > maxCodePoints
                ? value.offsetByCodePoints(0, count - maxCodePoints) : 0;
        return (start > 0 ? "…" : "") + safeDiagnosticPreview(value.substring(start), maxCodePoints);
    }

    /** Sends the complete protected source text in one ordinary translation request. */
    private Http.Response requestOnce(String text, Http.Request request, Http.Headers headers)
            throws Exception {
        boolean sse = cfg.streaming;
        String systemPrompt;
        if (cfg.systemPrompt.contains("{{target_language}}")) {
            systemPrompt = cfg.systemPrompt.replace("{{target_language}}", cfg.targetLang);
        } else if (Config.DEF_SYSTEM_PROMPT.equals(cfg.systemPrompt)) {
            systemPrompt = Config.defaultSystemPromptFor(cfg.targetLang);
        } else {
            systemPrompt = cfg.systemPrompt;
        }
        String body = Protocols.requestBody(cfg.protocol, text, systemPrompt, cfg.model,
                sse, cfg.protectionPromptEnabled, cfg.targetLang);
        buildHeaders(headers);

        request.checkCancelled();
        String url = Protocols.endpoint(cfg.protocol, cfg.apiUrl);
        Http.Response resp = http.postJson(url, body, headers, request,
                Protocols.createDecoder(cfg.protocol, sse));
        if (!resp.isSuccess()) {
            throw classifyHttpError(resp);
        }
        return resp;
    }

    /**
     * 取消正在进行中的请求：关闭该线程上的连接，让阻塞读立即返回，
     * 否则热重载/关闭后旧代次的请求会继续跑到超时。
     */
    @Override
    public void cancel(Thread worker) {
        if (worker == null) {
            return;
        }
        Http.Request request = active.get(worker);
        try {
            if (request != null) {
                request.cancel();
            }
        } finally {
            worker.interrupt();
        }
    }

    /**
     * 校验一份配置是否可用于发起请求。
     *
     * <p>抽成静态方法供设置页的离线自检复用，避免两处各写一套校验而产生不一致。
     * 请求体与译文提取由 {@link Protocols} 按协议内置实现，无需再校验模板与路径。</p>
     *
     * @return 已解析的额外请求头，真实请求在同一对象上追加协议认证头
     */
    public static Http.Headers validateConfig(Config.Snapshot cfg) throws TranslationException {
        if (cfg.configError != null) {
            throw new TranslationException(TranslationException.Kind.CONFIG, cfg.configError);
        }
        if (cfg.apiUrl == null || cfg.apiUrl.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.CONFIG, "API URL 未配置");
        }
        String u = cfg.apiUrl.trim().toLowerCase(java.util.Locale.US);
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "API URL 必须以 http:// 或 https:// 开头");
        }
        try {
            java.net.URI endpoint = new java.net.URI(Protocols.endpoint(cfg.protocol, cfg.apiUrl));
            if (endpoint.getHost() == null || endpoint.getHost().isEmpty()
                    || endpoint.getPort() < -1 || endpoint.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid HTTP endpoint authority");
            }
            endpoint.toURL();
        } catch (java.net.URISyntaxException | java.net.MalformedURLException
                 | IllegalArgumentException invalid) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "API 基础地址或协议无效", invalid);
        }
        return Http.Headers.parse(cfg.extraHeaders);
    }

    /**
     * 在已校验、已解析的请求头上追加协议头。用户已自定义的同名头不覆盖。
     *
     * <p>OpenAI 系协议用 {@code Authorization: Bearer}；Anthropic Messages
     * 用 {@code x-api-key} 且必须携带版本头 {@code anthropic-version}。</p>
     */
    private void buildHeaders(Http.Headers headers) {
        boolean anthropic = Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(cfg.protocol);
        if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()) {
            if (anthropic) {
                if (headers.get("x-api-key") == null) {
                    headers.add("x-api-key", cfg.apiKey.trim());
                }
            } else if (headers.get("Authorization") == null) {
                headers.add("Authorization", "Bearer " + cfg.apiKey.trim());
            }
        }
        if (anthropic && headers.get("anthropic-version") == null) {
            headers.add("anthropic-version", "2023-06-01");
        }
    }

    /** Classifies HTTP status, logs its exact error body under the saved gate, and retains it for UI. */
    private TranslationException classifyHttpError(Http.Response resp) {
        int status = resp.status;
        if (resp.body != null && !resp.body.isEmpty()) {
            ProjectLog.warn("api.upstream.error httpStatus=" + status + " body=" + resp.body);
        }
        TranslationException.Kind kind = status == 429
                ? TranslationException.Kind.RATE_LIMIT : TranslationException.Kind.HTTP;
        TranslationException.Reason reason = status == 429
                ? TranslationException.Reason.RATE_LIMITED : TranslationException.Reason.HTTP_ERROR;
        String message = status == 429 ? "限流"
                : status == 401 || status == 403 ? "认证失败（请检查 API 密钥）"
                : status >= 500 ? "服务端错误" : "请求失败";
        return new UpstreamHttpException(kind, reason, message, status,
                resp.body == null ? "" : resp.body);
    }
}
