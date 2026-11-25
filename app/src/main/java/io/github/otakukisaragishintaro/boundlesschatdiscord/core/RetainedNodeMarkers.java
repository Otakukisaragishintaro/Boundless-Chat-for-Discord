package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Requires exact syntax, identity and multiplicity while allowing occurrence order to
     * change with translated sentence/phrase order. Every generated identity must occur
     * exactly once; unknown, missing and duplicated identities remain invalid.
     */
    public static boolean matchesExpected(String translated, List<String> expected) {
        List<String> actual = read(translated);
        if (actual == null || expected == null || actual.size() != expected.size()) return false;
        Set<String> expectedIdentities = new HashSet<>(expected);
        Set<String> actualIdentities = new HashSet<>(actual);
        return expectedIdentities.size() == expected.size()
                && actualIdentities.size() == actual.size()
                && expectedIdentities.equals(actualIdentities);
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

    public static boolean matchesSource(String translated, String source) {
        List<String> expected = read(source);
        return expected != null && matchesExpected(translated, expected);
    }
}
