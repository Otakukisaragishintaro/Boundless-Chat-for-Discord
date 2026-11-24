package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RetainedNodeMarkers {
    private static final Pattern EXACT = Pattern.compile("<keep[1-9][0-9]*/>");
    private static final Pattern STYLE = Pattern.compile("</?x[1-9][0-9]*>");
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
    public static boolean matchesSource(String translated, String source) {
        return translated != null && source != null && matchesExpected(translated, read(source));
    }
}