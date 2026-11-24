package io.github.otakukisaragishintaro.boundlesschatdiscord.translate;

import androidx.annotation.Nullable;

import java.util.concurrent.Callable;

import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Config;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.DiscordTokenMarkers;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.EnglishDetector;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.TranslationScope;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.ProjectLog;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.TranslationException;

/** Settings tests share the production request, decoder and cancellation owner. */
public final class ApiTester {
    public enum TestKind { CONFIGURATION, CONNECTION, TRANSLATION, SEND_TRANSLATION }

    /** Failure metadata plus the unmodified non-2xx HTTP error body for settings-test display. */
    public static final class Failure {
        public final TranslationException.Kind kind;
        public final TranslationException.Reason reason;
        public final int httpStatus;
        public final String exceptionType;
        @Nullable public final String upstreamErrorBody;
        public final String detail;
        public final String stage;
        @Nullable public final String sourcePreview;
        @Nullable public final String requestPreview;
        @Nullable public final String translatedPreview;

        private Failure(TranslationException.Kind kind, TranslationException.Reason reason,
                        int httpStatus, String exceptionType, @Nullable String upstreamErrorBody,
                        String detail, String stage, @Nullable String sourcePreview,
                        @Nullable String requestPreview, @Nullable String translatedPreview) {
            this.kind = kind;
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.exceptionType = exceptionType;
            this.upstreamErrorBody = upstreamErrorBody;
            this.detail = detail == null ? "" : detail;
            this.stage = stage == null ? "" : stage;
            this.sourcePreview = sourcePreview;
            this.requestPreview = requestPreview;
            this.translatedPreview = translatedPreview;
        }

        private static Failure from(Throwable failure) {
            if (failure instanceof TranslationException) {
                TranslationException error = (TranslationException) failure;
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                Throwable upstream = error;
                while (upstream != null && !(upstream instanceof JsonApiEngine.UpstreamHttpException)) {
                    upstream = upstream.getCause();
                }
                String body = upstream instanceof JsonApiEngine.UpstreamHttpException
                        ? ((JsonApiEngine.UpstreamHttpException) upstream).responseBody : null;
                JsonApiEngine.DiagnosticException diagnostic =
                        error instanceof JsonApiEngine.DiagnosticException
                                ? (JsonApiEngine.DiagnosticException) error : null;
                String stage = diagnostic != null ? diagnostic.stage
                        : error.reason() == TranslationException.Reason.SCOPE_CONTEXT_REQUIRED
                                || error.reason() == TranslationException.Reason.SCOPE_DENIED
                                ? "scope_filter"
                        : error.reason() == TranslationException.Reason.NO_TRANSLATABLE_TEXT
                                ? "language_detection" : "request_or_preflight";
                boolean showDetail = error.reason() == TranslationException.Reason.TRANSLATION_INVALID
                        || error.reason() == TranslationException.Reason.NO_TRANSLATABLE_TEXT
                        || error.reason() == TranslationException.Reason.SCOPE_CONTEXT_REQUIRED
                        || error.reason() == TranslationException.Reason.SCOPE_DENIED;
                return new Failure(error.kind(), error.reason(), error.httpStatus(),
                        cause.getClass().getSimpleName(), body,
                        showDetail ? safeValidationDetail(error.getMessage()) : "", stage,
                        null, diagnostic == null ? null : diagnostic.requestPreview,
                        diagnostic == null ? null : diagnostic.translatedPreview);
            }
            return new Failure(TranslationException.Kind.UNKNOWN,
                    TranslationException.Reason.UNKNOWN_FAILURE, 0,
                    failure.getClass().getSimpleName(), null, "",
                    "request_or_preflight", null, null, null);
        }

        private static Failure cancelled(int httpStatus) {
            return new Failure(TranslationException.Kind.CANCELLED,
                    TranslationException.Reason.CANCELLED, httpStatus, "TranslationException", null, "",
                    "cancelled", null, null, null);
        }
    }

    public static final class Result {
        public final boolean ok;
        @Nullable public final String translated;
        public final long elapsedMs;
        public final TestKind testKind;
        @Nullable public final Failure failure;

        private Result(TestKind testKind, @Nullable String translated, long elapsedMs,
                       @Nullable Failure failure) {
            this.ok = failure == null;
            this.translated = failure == null
                    && (testKind == TestKind.TRANSLATION || testKind == TestKind.SEND_TRANSLATION)
                    ? translated : null;
            this.elapsedMs = elapsedMs;
            this.testKind = testKind;
            this.failure = failure;
        }
    }

    /** Fixed request probe; quoting/newlines also exercise request JSON escaping. */
    public static final String DEFAULT_TEST_TEXT = "Hello, \"world\"\\\nThis is a test.";

    private ApiTester() {}

    /** Create before executor submission so cancellation also covers queued work. */
    public static Call newCall(Config.Snapshot cfg, TestKind testKind, String text) {
        return newCall(cfg, testKind, text, "", "");
    }

    public static Call newCall(Config.Snapshot cfg, TestKind testKind, String text,
                               String testGuildId, String testChannelId) {
        Config.Snapshot requestConfig = testKind == TestKind.SEND_TRANSLATION
                ? cfg.forSendTranslation() : cfg;
        return new Call(new JsonApiEngine(requestConfig), testKind, text, cfg,
                testGuildId, testChannelId);
    }

    /** One-shot owner; page cancellation actively disconnects HTTP I/O. */
    public static final class Call implements Callable<Result> {
        private final JsonApiEngine engine;
        private final TestKind testKind;
        private final String text;
        private final String protocol;
        private final boolean streaming;
        private final Config.Snapshot sourceConfig;
        private final String testGuildId;
        private final String testChannelId;
        private boolean used;
        private boolean cancelled;
        @Nullable private Thread worker;

        Call(JsonApiEngine engine, TestKind testKind, String text, Config.Snapshot cfg) {
            this(engine, testKind, text, cfg, "", "");
        }

        Call(JsonApiEngine engine, TestKind testKind, String text, Config.Snapshot cfg,
             String testGuildId, String testChannelId) {
            if (testKind != TestKind.CONNECTION && testKind != TestKind.TRANSLATION
                    && testKind != TestKind.SEND_TRANSLATION) {
                throw new IllegalArgumentException("Network tests require a network test kind");
            }
            this.engine = engine;
            this.testKind = testKind;
            this.text = testKind == TestKind.CONNECTION ? DEFAULT_TEST_TEXT : text;
            this.protocol = diagnosticProtocol(cfg.protocol);
            this.streaming = cfg.streaming;
            this.sourceConfig = cfg;
            this.testGuildId = testGuildId == null ? "" : testGuildId.trim();
            this.testChannelId = testChannelId == null ? "" : testChannelId.trim();
        }

        @Override
        public Result call() {
            long start = System.nanoTime();
            synchronized (this) {
                if (used) throw new IllegalStateException("API test calls cannot be reused");
                used = true;
                if (cancelled) {
                    Result result = new Result(testKind, null, elapsed(start), Failure.cancelled(0));
                    logResult(result);
                    return result;
                }
                // Publish before registration; cancel interrupts the pre-attach gap.
                worker = Thread.currentThread();
            }
            ProjectLog.info("api.test.start test=" + testKind + " protocol=" + protocol
                    + " streaming=" + streaming);
            Result result;
            int responseStatus = 0;
            try {
                if (testKind == TestKind.TRANSLATION) {
                    enforceTestScope(sourceConfig.translationScope, testGuildId, testChannelId);
                    String detectionText = DiscordTokenMarkers.protect(text,
                            sourceConfig.protectMentions, sourceConfig.protectChannelsEmojis,
                            sourceConfig.protectUrls).text;
                    EnglishDetector.Verdict verdict = text == null || text.trim().isEmpty() ? null
                            : EnglishDetector.evaluate(detectionText, 1, sourceConfig.targetLang);
                    if (verdict != null && !verdict.shouldTranslate) {
                        throw new TranslationException(TranslationException.Kind.EMPTY,
                                TranslationException.Reason.NO_TRANSLATABLE_TEXT,
                                "语言检测跳过：" + verdict.reason.name());
                    }
                    // Same production entry point used by Scheduler.Engine: Discord token
                    // protection, paragraph chunking, request/decoder handling, integrity
                    // validation, and protected-token restoration all run here.
                    result = new Result(testKind, engine.translateForTest(text), elapsed(start), null);
                } else if (testKind == TestKind.SEND_TRANSLATION) {
                    result = new Result(testKind, engine.translateForTest(text), elapsed(start), null);
                } else {
                    JsonApiEngine.Exchange exchange = engine.execute(text, false);
                    responseStatus = exchange.httpStatus;
                    result = new Result(testKind, exchange.translated, elapsed(start), null);
                }
            } catch (Exception failure) {
                Failure diagnostic = Failure.from(failure);
                if (diagnostic.sourcePreview == null) {
                    diagnostic = new Failure(diagnostic.kind, diagnostic.reason,
                            diagnostic.httpStatus, diagnostic.exceptionType,
                            diagnostic.upstreamErrorBody, diagnostic.detail, diagnostic.stage,
                            preview(text, 1000), diagnostic.requestPreview,
                            diagnostic.translatedPreview);
                }
                result = new Result(testKind, null, elapsed(start), diagnostic);
            } finally {
                synchronized (this) {
                    // Always release ownership, including Errors handled by the Future boundary.
                    worker = null;
                }
            }
            synchronized (this) {
                if (cancelled && (result.failure == null
                        || result.failure.kind != TranslationException.Kind.CANCELLED)) {
                    // Preserve known status even if cancellation wins after successful decoding.
                    int status = result.failure == null ? responseStatus : result.failure.httpStatus;
                    result = new Result(testKind, null, elapsed(start), Failure.cancelled(status));
                }
            }
            logResult(result);
            return result;
        }

        public synchronized void cancel() {
            cancelled = true;
            if (worker != null) engine.cancel(worker);
        }
    }

    private static void enforceTestScope(TranslationScope scope, String guildId,
                                          String channelId) throws TranslationException {
        if (!scope.channels.isEmpty() && !TranslationScope.isValidId(channelId)) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    TranslationException.Reason.SCOPE_CONTEXT_REQUIRED,
                    "此翻译范围包含频道规则，请填写测试频道 ID。");
        }
        if (!scope.servers.isEmpty() && !TranslationScope.isValidId(guildId)) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    TranslationException.Reason.SCOPE_CONTEXT_REQUIRED,
                    "此翻译范围包含服务器规则，请填写测试服务器 ID。");
        }
        String effectiveChannel = TranslationScope.isValidId(channelId) ? channelId : "1";
        String effectiveGuild = TranslationScope.isValidId(guildId) ? guildId : null;
        if (!scope.allows(effectiveGuild, effectiveChannel)) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    TranslationException.Reason.SCOPE_DENIED,
                    "当前测试服务器/频道被翻译范围规则排除。");
        }
    }

    private static String preview(String value, int max) {
        if (value == null) return null;
        String safe = value.replace("\r", "\\\\r").replace("\n", "\\\\n")
                .replace("\t", "\\\\t");
        return safe.length() <= max ? safe : safe.substring(0, max) + "…";
    }

    /** Offline check consumes the same configuration validation, without network I/O. */
    public static Result selfCheck(Config.Snapshot cfg) {
        long start = System.nanoTime();
        ProjectLog.info("api.test.start test=CONFIGURATION protocol="
                + diagnosticProtocol(cfg.protocol) + " streaming=" + cfg.streaming);
        Result result;
        try {
            JsonApiEngine.validateConfig(cfg);
            result = new Result(TestKind.CONFIGURATION, null, elapsed(start), null);
        } catch (Exception failure) {
            result = new Result(TestKind.CONFIGURATION, null, elapsed(start), Failure.from(failure));
        }
        logResult(result);
        return result;
    }

    /** Keep the validation category visible without exposing source/response length counts. */
    private static String safeValidationDetail(String message) {
        return message == null ? "" : message.replaceAll("（[0-9]+/[0-9]+）", "");
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static String diagnosticProtocol(String protocol) {
        if (Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)
                || Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)
                || Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) return protocol;
        return "INVALID";
    }

    private static void logResult(Result result) {
        if (result.ok) {
            ProjectLog.info("api.test.complete test=" + result.testKind + " outcome=SUCCESS");
        } else {
            Failure failure = result.failure;
            ProjectLog.warn("api.test.complete test=" + result.testKind + " outcome=FAILURE kind="
                    + failure.kind + " reason=" + failure.reason + " httpStatus="
                    + failure.httpStatus + " errorClass=" + failure.exceptionType);
        }
    }
}
