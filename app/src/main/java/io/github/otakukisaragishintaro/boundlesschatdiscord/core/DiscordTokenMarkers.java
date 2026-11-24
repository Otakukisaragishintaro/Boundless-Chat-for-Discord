package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shields literal Discord mention/channel/custom-emoji/URL syntax while text is sent to a
 * translation provider. Existing native-node keep markers are left untouched; new
 * markers use the same integrity-checked namespace and are restored only after validation.
 */
public final class DiscordTokenMarkers {
    private static final Pattern TOKEN = Pattern.compile(
            "(<(?:@!?[0-9]{15,22}|@&[0-9]{15,22})>|@(?:everyone|here)\\b)"
                    + "|(<#[0-9]{15,22}>|<a?:[A-Za-z0-9_]{2,32}:[0-9]{15,22}>)"
                    + "|((?:https?://|www\\.)[^\\s<>]+)",
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

        /** Restore only markers introduced by this protection pass. */
        public String restore(String translated) {
            String result = translated;
            for (int i = 0; i < markers.size(); i++) {
                result = result.replace(markers.get(i), originals.get(i));
            }
            return result;
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
                    : matcher.group(2) != null ? protectChannelsAndEmojis : protectUrls;
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
