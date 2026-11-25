package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import androidx.annotation.Nullable;


/**
 * 单一文本翻译输入的防损坏校验（含格式与保留叶节点定位标记）。
 *
 * <p>{@link #plainTextViolation} 检查空文本、
 * 包装标签、模板占位符与长度比例异常，不把损坏内容送进节点树。</p>
 */
public final class Segments {

    /**
     * 模型幻觉出的模板占位符（如 {@code XXXXXX}）。
     *
     * <p>实测小模型在长重复文本上会输出 {@code SENSENOVA_..._HHMMSS_XXXXXX}
     * 这类占位符；正常译文不会出现 4 个以上连续大写 X。</p>
     */
    private static final Pattern PLACEHOLDER_RUN = Pattern.compile("X{4,}");

    private Segments() {
    }

    /**
     * Removes a single, exact pair of request-boundary tags when a model echoes the
     * entire wrapper around its answer. Embedded, partial, or nested tags are left
     * untouched so plainTextViolation() can reject them rather than corrupt content.
     */
    public static String stripOuterResponseWrapper(String translated, String source) {
        if (translated == null) return null;
        String value = translated.trim();
        boolean hasOpen = value.startsWith(Protocols.WRAPPER_OPEN);
        boolean hasClose = value.endsWith(Protocols.WRAPPER_CLOSE);
        if (hasOpen && hasClose) {
            String inner = value.substring(Protocols.WRAPPER_OPEN.length(),
                    value.length() - Protocols.WRAPPER_CLOSE.length());
            if (inner.contains(Protocols.WRAPPER_OPEN) || inner.contains(Protocols.WRAPPER_CLOSE)) {
                return translated;
            }
            return inner.trim();
        }
        // A provider may echo only the closing request boundary even when the answer
        // starts with ordinary translated text rather than a retained-node marker.
        // Strip that single unmatched terminal boundary only when the source itself
        // did not contain the literal tag; embedded or repeated tags remain untouched.
        boolean sourceContainsClose = source != null
                && source.contains(Protocols.WRAPPER_CLOSE);
        int closeIndex = value.indexOf(Protocols.WRAPPER_CLOSE);
        if (!hasOpen && hasClose && !sourceContainsClose
                && !value.contains(Protocols.WRAPPER_OPEN)
                && closeIndex == value.length() - Protocols.WRAPPER_CLOSE.length()) {
            return value.substring(0, closeIndex).trim();
        }
        return translated;
    }

    /**
     * 译文的防损坏校验：非空、无包装标签、原样保留结构定位、长度比例合理。
     *
     * <p>单链路在引擎侧返回前调用：复读刷屏（长度
     * 暴涨）、内容大面积丢失（长度骤减）、标记泄漏与模板占位符都在这里
     * 拦下，判失败由用户手动重试。</p>
     *
     * <p>长度比例只对较长的原文（≥40 字符）生效：正常中英互译长度在
     * 0.10×～5× 之外的极端比例才直接拒绝，避免不同语言长度差异造成过度误判；
     * 过短的文本比例噪声太大，不做校验。</p>
     *
     * @return 违规描述；合格返回 null
     */
    /** Backward-compatible default policy used by existing callers and tests. */
    @Nullable
    public static String plainTextViolation(@Nullable String translated, String source) {
        return plainTextViolation(translated, source,
                Config.DEF_CHECK_WRAPPER, Config.DEF_CHECK_PLACEHOLDERS,
                Config.DEF_CHECK_RETAINED_MARKERS, Config.DEF_CHECK_LENGTH,
                Config.DEF_MIN_LENGTH_PERCENT, Config.DEF_MAX_LENGTH_PERCENT);
    }

    /** Applies the user's independent response-integrity policy. */
    @Nullable
    public static String plainTextViolation(@Nullable String translated, String source,
                                            boolean checkWrapper, boolean checkPlaceholders,
                                            boolean checkRetainedMarkers, boolean checkLength,
                                            int minLengthPercent, int maxLengthPercent) {
        // An empty API response is never a usable translation and remains a hard protocol error.
        if (translated == null || translated.trim().isEmpty()) {
            return "译文为空";
        }
        if (checkWrapper && (translated.contains(Protocols.WRAPPER_OPEN)
                || translated.contains(Protocols.WRAPPER_CLOSE))) {
            return "译文包含包装标签";
        }
        if (checkPlaceholders && PLACEHOLDER_RUN.matcher(translated).find()) {
            return "译文含模板占位符";
        }
        if (checkRetainedMarkers && !RetainedNodeMarkers.matchesSource(translated, source)) {
            return "保留节点定位标记无效；"
                    + RetainedNodeMarkers.mismatchDiagnostic(translated, source);
        }
        String sourceWithoutKeep = RetainedNodeMarkers.withoutMarkers(source);
        String translationWithoutKeep = RetainedNodeMarkers.withoutMarkers(translated);
        if (translationWithoutKeep.trim().isEmpty()) return "译文为空";
        int n = sourceWithoutKeep.codePointCount(0, sourceWithoutKeep.length());
        if (checkLength && n >= 40) {
            int m = translationWithoutKeep.codePointCount(0, translationWithoutKeep.length());
            if ((long) m * 100L < (long) n * minLengthPercent) {
                return "译文长度异常（" + m + "/" + n + "）：疑似内容丢失";
            }
            if ((long) m * 100L > (long) n * maxLengthPercent) {
                return "译文长度异常（" + m + "/" + n + "）：疑似复读";
            }
        }
        return null;
    }
}
