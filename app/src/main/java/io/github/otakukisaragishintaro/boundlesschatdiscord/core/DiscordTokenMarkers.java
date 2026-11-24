package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shields Discord syntax, style boundaries, and optionally Unicode emoji with the same
 * <keepN/> markers used by retained content nodes. Markers are restored after validation.
 */
public final class DiscordTokenMarkers {
    private static final Pattern UNICODE_EMOJI = Pattern.compile(
            "\\p{So}(?:\\uFE0F)?(?:\\uD83C[\\uDFFB-\\uDFFF])?"
                    + "(?:\\u200D\\p{So}(?:\\uFE0F)?(?:\\uD83C[\\uDFFB-\\uDFFF])?)*"
                    + "|[0-9#*]\\uFE0F?\\u20E3");
    private static final Pattern TOKEN = Pattern.compile(
            "(<(?:@!?[0-9]{15,22}|@&[0-9]{15,22})>|@(?:everyone|here)\\b)"
                    + "|(<#[0-9]{15,22}>|<a?:[A-Za-z0-9_]{2,32}:[0-9]{15,22}>)"
                    + "|((?:https?://|www\\.)[^\\s<>]+)"
                    + "|(" + UNICODE_EMOJI.pattern() + ")"
                    + "|((?-i:</?x[1-9][0-9]*>))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEEP = Pattern.compile("<keep([1-9][0-9]*)/>");

    private DiscordTokenMarkers() { }

    public static final class Protection {
        public final String text;
        private final List<String> markers;
        private final List<String> originals;

        private Protection(String text, List<String> markers, List<String> originals) {
            this.text = text;
            this.markers = markers;
            this.originals = originals;
        }

        /**
         * Restore only markers introduced by this protection pass. Their presence, uniqueness,
         * and order are a mandatory safety check even when the optional retained-node validator
         * is disabled; otherwise a missing Discord mention or URL could be silently discarded.
         */
        public String restore(String translated) throws TranslationException {
            if (!markers.isEmpty()) {
                if (translated == null) {
                    throw invalidRestoration();
                }
                int previousPosition = -1;
                for (String marker : markers) {
                    int position = translated.indexOf(marker);
                    if (position < 0 || position <= previousPosition
                            || translated.indexOf(marker, position + marker.length()) >= 0) {
                        throw invalidRestoration();
                    }
                    previousPosition = position;
                }
            }
            String result = translated;
            for (int i = 0; i < markers.size(); i++) {
                result = result.replace(markers.get(i), originals.get(i));
            }
            return result;
        }

        private static TranslationException invalidRestoration() {
            return new TranslationException(TranslationException.Kind.PARSE,
                    TranslationException.Reason.TRANSLATION_INVALID,
                    "译文未完整保留 Discord 保护标记，已拒绝恢复以避免内容丢失");
        }

        public int tokenCount() {
            return markers.size();
        }
    }

    /**
     * Protect blank-line separators inside a grouped request. This lets multiple whole
     * paragraphs share context without allowing the provider to normalize their spacing.
     */
    public static Protection protectBlankLineSeparators(String source) {
        if (source == null || source.isEmpty()) {
            return new Protection(source, new ArrayList<>(), new ArrayList<>());
        }
        BlankLines.Split split = BlankLines.split(source);
        if (split.separators.isEmpty()) {
            return new Protection(source, new ArrayList<>(), new ArrayList<>());
        }

        int next = 1;
        Matcher existing = KEEP.matcher(source);
        while (existing.find()) {
            try {
                next = Math.max(next, Integer.parseInt(existing.group(1)) + 1);
            } catch (NumberFormatException exhausted) {
                return new Protection(source, new ArrayList<>(), new ArrayList<>());
            }
        }

        StringBuilder protectedText = new StringBuilder(source.length());
        List<String> markers = new ArrayList<>();
        List<String> originals = new ArrayList<>();
        for (int i = 0; i < split.chunks.size(); i++) {
            protectedText.append(split.chunks.get(i));
            if (i < split.separators.size()) {
                String marker = "<keep" + next++ + "/>";
                markers.add(marker);
                originals.add(split.separators.get(i));
                protectedText.append(marker);
            }
        }
        return new Protection(protectedText.toString(), markers, originals);
    }

    /** Replace selected literal Discord syntax with unique keep markers before translation. */
    public static Protection protect(String source) {
        return protect(source, true, true, true);
    }

    public static Protection protect(String source, boolean protectMentions,
                                     boolean protectChannelsAndEmojis, boolean protectUrls) {
        if (source == null || source.isEmpty()) {
            return new Protection(source, new ArrayList<>(), new ArrayList<>());
        }

        int next = 1;
        Matcher existing = KEEP.matcher(source);
        while (existing.find()) {
            try {
                next = Math.max(next, Integer.parseInt(existing.group(1)) + 1);
            } catch (NumberFormatException exhausted) {
                // An impossibly large source marker is rejected by the normal integrity guard.
                return new Protection(source, new ArrayList<>(), new ArrayList<>());
            }
        }

        Matcher matcher = TOKEN.matcher(source);
        StringBuffer protectedText = new StringBuffer(source.length());
        List<String> markers = new ArrayList<>();
        List<String> originals = new ArrayList<>();
        while (matcher.find()) {
            boolean selected = matcher.group(1) != null ? protectMentions
                    : matcher.group(2) != null ? protectChannelsAndEmojis
                    : matcher.group(3) != null ? protectUrls
                    : matcher.group(4) != null ? protectChannelsAndEmojis : true;
            if (selected) {
                String marker = "<keep" + next++ + "/>";
                markers.add(marker);
                originals.add(matcher.group());
                matcher.appendReplacement(protectedText, Matcher.quoteReplacement(marker));
            } else {
                matcher.appendReplacement(protectedText, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(protectedText);
        return new Protection(protectedText.toString(), markers, originals);
    }
}
