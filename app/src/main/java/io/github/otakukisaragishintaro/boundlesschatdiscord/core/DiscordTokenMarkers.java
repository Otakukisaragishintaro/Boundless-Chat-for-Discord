package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protects literal Discord tokens with movable identity placeholders, while keeping
 * native-node/style/paragraph anchors in the ordered <keepN/> namespace.
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
                    + "|((?-i:</?x[1-9][0-9]*>))"
                    + "|(⟦BC_TOKEN_[^⟧\\r\\n]{1,48}⟧)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEEP = Pattern.compile("<keep([1-9][0-9]*)/>");
    private static final Pattern TOKEN_FAMILY =
            Pattern.compile("⟦BC_TOKEN_[^⟧\\r\\n]{1,64}⟧", Pattern.CASE_INSENSITIVE);

    private DiscordTokenMarkers() { }

    public static final class Protection {
        public final String text;
        private final List<String> movableMarkers;
        private final List<String> movableOriginals;
        private final List<String> orderedMarkers;
        private final List<String> orderedOriginals;
        private final boolean validateMovableTokens;

        private Protection(String text, List<String> movableMarkers,
                           List<String> movableOriginals, List<String> orderedMarkers,
                           List<String> orderedOriginals, boolean validateMovableTokens) {
            this.text = text;
            this.movableMarkers = movableMarkers;
            this.movableOriginals = movableOriginals;
            this.orderedMarkers = orderedMarkers;
            this.orderedOriginals = orderedOriginals;
            this.validateMovableTokens = validateMovableTokens;
        }

        /**
         * Movable placeholders are matched by identity, not position: translation may
         * reorder them with the sentence. Structural <keepN/> anchors remain ordered.
         */
        public String restore(String translated) throws TranslationException {
            if (translated == null) {
                if (!movableMarkers.isEmpty() || !orderedMarkers.isEmpty()) {
                    throw invalidRestoration();
                }
                return null;
            }

            if (validateMovableTokens) {
                Map<String, Integer> expected = counts(movableMarkers);
                Map<String, Integer> actual = new HashMap<>();
                Matcher tokenMatcher = TOKEN_FAMILY.matcher(translated);
                while (tokenMatcher.find()) {
                    String marker = tokenMatcher.group();
                    actual.put(marker, actual.containsKey(marker) ? actual.get(marker) + 1 : 1);
                }
                if (!expected.equals(actual)) {
                    throw invalidRestoration();
                }
            }

            int previousPosition = -1;
            for (String marker : orderedMarkers) {
                int position = translated.indexOf(marker);
                if (position < 0 || position <= previousPosition
                        || translated.indexOf(marker, position + marker.length()) >= 0) {
                    throw invalidRestoration();
                }
                previousPosition = position;
            }

            String result = translated;
            for (int i = 0; i < movableMarkers.size(); i++) {
                result = result.replace(movableMarkers.get(i), movableOriginals.get(i));
            }
            for (int i = 0; i < orderedMarkers.size(); i++) {
                result = result.replace(orderedMarkers.get(i), orderedOriginals.get(i));
            }
            return result;
        }

        private static Map<String, Integer> counts(List<String> values) {
            Map<String, Integer> result = new HashMap<>();
            for (String value : values) {
                result.put(value, result.containsKey(value) ? result.get(value) + 1 : 1);
            }
            return result;
        }

        private static TranslationException invalidRestoration() {
            return new TranslationException(TranslationException.Kind.PARSE,
                    TranslationException.Reason.TRANSLATION_INVALID,
                    "译文未完整保留保护占位符，或结构锚点顺序已损坏；已拒绝恢复");
        }

        public int tokenCount() {
            return movableMarkers.size() + orderedMarkers.size();
        }
    }

    /** Replace selected literal Discord syntax with movable identity placeholders. */
    public static Protection protect(String source) {
        return protect(source, true, true, true);
    }

    public static Protection protect(String source, boolean protectMentions,
                                     boolean protectChannelsAndEmojis, boolean protectUrls) {
        if (source == null || source.isEmpty()) return empty(source);

        int nextKeep = nextKeepId(source);
        if (nextKeep < 0) return empty(source);
        String tokenPrefix = "⟦BC_TOKEN_";
        while (source.contains(tokenPrefix)) tokenPrefix = tokenPrefix + "X_";

        Matcher matcher = TOKEN.matcher(source);
        StringBuffer protectedText = new StringBuffer(source.length());
        List<String> movableMarkers = new ArrayList<>();
        List<String> movableOriginals = new ArrayList<>();
        List<String> orderedMarkers = new ArrayList<>();
        List<String> orderedOriginals = new ArrayList<>();
        int nextToken = 1;

        while (matcher.find()) {
            boolean styleBoundary = matcher.group(5) != null;
            boolean selected = matcher.group(1) != null ? protectMentions
                    : matcher.group(2) != null ? protectChannelsAndEmojis
                    : matcher.group(3) != null ? protectUrls
                    : matcher.group(4) != null ? protectChannelsAndEmojis
                    : matcher.group(6) != null || styleBoundary;
            if (!selected) {
                matcher.appendReplacement(protectedText,
                        Matcher.quoteReplacement(matcher.group()));
            } else if (styleBoundary) {
                String marker = "<keep" + nextKeep++ + "/>";
                orderedMarkers.add(marker);
                orderedOriginals.add(matcher.group());
                matcher.appendReplacement(protectedText, Matcher.quoteReplacement(marker));
            } else {
                String marker = tokenPrefix + nextToken++ + "⟧";
                movableMarkers.add(marker);
                movableOriginals.add(matcher.group());
                matcher.appendReplacement(protectedText, Matcher.quoteReplacement(marker));
            }
        }
        matcher.appendTail(protectedText);
        return new Protection(protectedText.toString(), movableMarkers, movableOriginals,
                orderedMarkers, orderedOriginals, true);
    }

    private static int nextKeepId(String source) {
        int next = 1;
        Matcher existing = KEEP.matcher(source);
        while (existing.find()) {
            try {
                int parsed = Integer.parseInt(existing.group(1));
                if (parsed == Integer.MAX_VALUE) return -1;
                next = Math.max(next, parsed + 1);
            } catch (NumberFormatException exhausted) {
                return -1;
            }
        }
        return next;
    }

    private static Protection empty(String source) {
        return new Protection(source, new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), false);
    }
}