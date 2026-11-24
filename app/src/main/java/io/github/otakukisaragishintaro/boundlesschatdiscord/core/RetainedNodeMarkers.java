package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reserved, content-free occurrence anchors for retained native leaves. Never repairs markers. */
public final class RetainedNodeMarkers {
    private static final Pattern PREFIX = Pattern.compile("<\\s*/?\\s*keep", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT = Pattern.compile("<keep[1-9][0-9]*/>");
    private static final Pattern STYLE = Pattern.compile("</?x[1-9][0-9]*>");

    private RetainedNodeMarkers() { }

    /** Source text must not collide with the native-node anchor namespace. */
    public static boolean hasReservedPrefix(String text) {
        return text != null && PREFIX.matcher(text).find();
    }

    /** One syntax check shared by engine and reconstruction; null means malformed reserved syntax. */
    private static List<String> read(String text) {
        // Validate reserved syntax across styled chunks too, not only within individual text nodes.
        text = STYLE.matcher(text).replaceAll("");
        List<String> markers = new ArrayList<>();
        Matcher prefix = PREFIX.matcher(text);
        while (prefix.find()) {
            Matcher marker = EXACT.matcher(text);
            marker.region(prefix.start(), text.length());
            if (!marker.lookingAt()) return null;
            markers.add(marker.group());
        }
        return markers;
    }

    /** Requires exact syntax, multiplicity and occurrence order, including rejection of extra markers. */
    public static boolean matchesExpected(String translated, List<String> expected) {
        List<String> actual = read(translated);
        return actual != null && actual.equals(expected);
    }

    /** Analysis only: generated anchor characters are not natural-language text. */
    public static String withoutMarkers(String text) {
        return EXACT.matcher(text).replaceAll("");
    }

    /** Detection only: unwrap styles without splitting words; anchors keep adjacent URL/text apart. */
    public static String maskedForDetection(String text) {
        text = STYLE.matcher(text).replaceAll("");
        return EXACT.matcher(text).replaceAll(" ");
    }

    private static final Pattern PROVIDER_MARKER = Pattern.compile("⟦BC_KEEP_([1-9][0-9]*)⟧");
    private static final Pattern PROVIDER_NAMESPACE = Pattern.compile("(?i)BC[_ -]?KEEP");

    /**
     * Per-request representation sent to translation providers. Internal <keepN/> anchors
     * never cross the API boundary; they are encoded as non-tag Unicode-bracket tokens and
     * restored only after exact order and multiplicity validation.
     */
    public static final class ProviderMarkers {
        public final String text;
        private final List<String> providerMarkers;
        private final List<String> internalMarkers;

        private ProviderMarkers(String text, List<String> providerMarkers,
                                List<String> internalMarkers) {
            this.text = text;
            this.providerMarkers = providerMarkers;
            this.internalMarkers = internalMarkers;
        }

        /** Null means the provider changed, removed, duplicated, or leaked a reserved marker. */
        public String restore(String translated) {
            if (translated == null) return null;
            Matcher matcher = PROVIDER_MARKER.matcher(translated);
            List<String> actual = new ArrayList<>();
            while (matcher.find()) actual.add(matcher.group());
            String withoutValidMarkers = matcher.reset().replaceAll("");
            if (!actual.equals(providerMarkers)
                    || PROVIDER_NAMESPACE.matcher(withoutValidMarkers).find()
                    || hasReservedPrefix(translated)) {
                return null;
            }
            String restored = translated;
            for (int i = 0; i < providerMarkers.size(); i++) {
                restored = restored.replace(providerMarkers.get(i), internalMarkers.get(i));
            }
            return restored;
        }
    }

    /**
     * Encode every internal retained-node anchor before the request is sent to a provider.
     * A malformed internal marker or collision with the provider-only namespace is rejected.
     */
    public static ProviderMarkers encodeForProvider(String source) {
        if (source == null) return null;
        if (read(source) == null || PROVIDER_NAMESPACE.matcher(source).find()) return null;

        Matcher matcher = EXACT.matcher(source);
        StringBuffer encoded = new StringBuffer(source.length());
        List<String> providerMarkers = new ArrayList<>();
        List<String> internalMarkers = new ArrayList<>();
        int index = 1;
        while (matcher.find()) {
            String providerMarker = "⟦BC_KEEP_" + index++ + "⟧";
            providerMarkers.add(providerMarker);
            internalMarkers.add(matcher.group());
            matcher.appendReplacement(encoded, Matcher.quoteReplacement(providerMarker));
        }
        matcher.appendTail(encoded);
        return new ProviderMarkers(encoded.toString(), providerMarkers, internalMarkers);
    }

    public static boolean matchesSource(String translated, String source) {
        List<String> expected = read(source);
        return expected != null && matchesExpected(translated, expected);
    }
}
