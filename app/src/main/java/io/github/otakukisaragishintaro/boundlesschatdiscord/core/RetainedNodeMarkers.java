package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RetainedNodeMarkers {
    private static final Pattern EXACT = Pattern.compile("<keep[1-9][0-9]*/>");
    private static final Pattern STYLE = Pattern.compile("</?x[1-9][0-9]*>");
    private static final Pattern MALFORMED_KEEP = Pattern.compile(
            "<\\s*/?\\s*keep[^>\\r\\n]{0,32}>", Pattern.CASE_INSENSITIVE);
    private static final int MAX_DIAGNOSTIC_MARKERS = 80;
    private RetainedNodeMarkers() { }

    /** Only complete registered markers participate; emoticons and incomplete angle text are ordinary text. */
    public static boolean hasReservedPrefix(String text) {
        return text != null && EXACT.matcher(text).find();
    }
    private static List<String> read(String text) {
        List<String> markers = new ArrayList<>();
        Matcher m = EXACT.matcher(text);
        while (m.find()) markers.add(m.group());
        return markers;
    }
    public static boolean matchesExpected(String translated, List<String> expected) {
        return translated != null && read(translated).equals(expected);
    }
    public static String withoutMarkers(String text) {
        return EXACT.matcher(text).replaceAll("");
    }
    public static String maskedForDetection(String text) {
        return EXACT.matcher(STYLE.matcher(text).replaceAll("")).replaceAll(" ");
    }

    /**
     * Returns a bounded, content-free explanation of a retained-marker mismatch.
     * Marker IDs are structural identifiers, not message text; no surrounding source
     * or translated content is included in this diagnostic.
     */
    public static String mismatchDiagnostic(String translated, String source) {
        List<String> expected = source == null ? new ArrayList<>() : read(source);
        List<String> actual = translated == null ? new ArrayList<>() : read(translated);
        int firstMismatch = -1;
        int shared = Math.min(expected.size(), actual.size());
        for (int i = 0; i < shared; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                firstMismatch = i;
                break;
            }
        }
        if (firstMismatch < 0 && expected.size() != actual.size()) {
            firstMismatch = shared;
        }

        Map<String, Integer> expectedCounts = counts(expected);
        Map<String, Integer> actualCounts = counts(actual);
        List<String> missing = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
            int actualCount = actualCounts.containsKey(entry.getKey())
                    ? actualCounts.get(entry.getKey()) : 0;
            if (actualCount < entry.getValue()) missing.add(entry.getKey()
                    + "x" + (entry.getValue() - actualCount));
        }
        for (Map.Entry<String, Integer> entry : actualCounts.entrySet()) {
            int expectedCount = expectedCounts.containsKey(entry.getKey())
                    ? expectedCounts.get(entry.getKey()) : 0;
            if (entry.getValue() > expectedCount) {
                unexpected.add(entry.getKey() + "x" + (entry.getValue() - expectedCount));
                if (expectedCount > 0) duplicated.add(entry.getKey()
                        + "x" + (entry.getValue() - expectedCount));
            }
        }
        List<String> malformed = malformedMarkers(translated);
        String mismatchType = expected.equals(actual) ? "NONE"
                : missing.isEmpty() && unexpected.isEmpty() ? "ORDER"
                : missing.isEmpty() ? "UNEXPECTED_OR_DUPLICATE"
                : unexpected.isEmpty() ? "MISSING" : "MISSING_AND_UNEXPECTED";
        String expectedAt = firstMismatch >= 0 && firstMismatch < expected.size()
                ? expected.get(firstMismatch) : "END";
        String actualAt = firstMismatch >= 0 && firstMismatch < actual.size()
                ? actual.get(firstMismatch) : "END";
        return "markerDiagnostic={type=" + mismatchType
                + ",expectedCount=" + expected.size()
                + ",actualCount=" + actual.size()
                + ",firstMismatchIndex=" + (firstMismatch < 0 ? 0 : firstMismatch + 1)
                + ",expectedAt=" + expectedAt + ",actualAt=" + actualAt
                + ",expectedSequence=" + formatList(expected)
                + ",actualSequence=" + formatList(actual)
                + ",missing=" + formatList(missing)
                + ",unexpected=" + formatList(unexpected)
                + ",duplicated=" + formatList(duplicated)
                + ",malformed=" + formatList(malformed) + "}";
    }

    private static Map<String, Integer> counts(List<String> markers) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String marker : markers) {
            Integer count = result.get(marker);
            result.put(marker, count == null ? 1 : count + 1);
        }
        return result;
    }

    private static List<String> malformedMarkers(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        Matcher matcher = MALFORMED_KEEP.matcher(text);
        while (matcher.find() && result.size() < MAX_DIAGNOSTIC_MARKERS) {
            String token = matcher.group();
            if (!EXACT.matcher(token).matches()) result.add(token);
        }
        return result;
    }

    private static String formatList(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        int limit = Math.min(values.size(), MAX_DIAGNOSTIC_MARKERS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) result.append(',');
            result.append(values.get(i));
        }
        if (values.size() > limit) result.append(",…+").append(values.size() - limit);
        return result.append(']').toString();
    }

    public static boolean matchesSource(String translated, String source) {
        return translated != null && source != null && matchesExpected(translated, read(source));
    }
}