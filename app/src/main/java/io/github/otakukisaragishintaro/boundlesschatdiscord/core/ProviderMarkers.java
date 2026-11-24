package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opaque placeholders for all registered internal structure tokens at the provider boundary. */
public final class ProviderMarkers {
    private static final Pattern INTERNAL = Pattern.compile(
            "<keep[1-9][0-9]*/>|</?x[1-9][0-9]*>|"
                    + "\\p{So}(?:\\uFE0F)?(?:\\uD83C[\\uDFFB-\\uDFFF])?"
                    + "(?:\\u200D\\p{So}(?:\\uFE0F)?(?:\\uD83C[\\uDFFB-\\uDFFF])?)*"
                    + "|[0-9#*]\\uFE0F?\\u20E3");
    private ProviderMarkers() { }

    public static final class Protection {
        public final String text;
        private final String prefix;
        private final List<String> markers;
        private final List<String> originals;
        private Protection(String text, String prefix, List<String> markers, List<String> originals) {
            this.text = text; this.prefix = prefix; this.markers = markers; this.originals = originals;
        }
        public String restore(String translated) throws TranslationException {
            if (translated == null) {
                if (markers.isEmpty()) return null;
                throw invalid();
            }
            Matcher m = Pattern.compile(Pattern.quote(prefix) + "[1-9][0-9]*⟧").matcher(translated);
            List<String> actual = new ArrayList<>();
            while (m.find()) actual.add(m.group());
            if (!actual.equals(markers)) throw invalid();
            String result = translated;
            for (int i=0;i<markers.size();i++) result = result.replace(markers.get(i), originals.get(i));
            return result;
        }
        private static TranslationException invalid() {
            return new TranslationException(TranslationException.Kind.PARSE,
                    TranslationException.Reason.TRANSLATION_INVALID,
                    "API returned missing, duplicated, reordered, or damaged protected placeholders");
        }
    }

    public static Protection protect(String source) {
        if (source == null) source = "";
        int n=0; String prefix;
        do { prefix = "⟦BCP" + n++ + "_"; } while (source.contains(prefix));
        Matcher m=INTERNAL.matcher(source);
        StringBuilder out=new StringBuilder(source.length());
        List<String> markers=new ArrayList<>(), originals=new ArrayList<>();
        int cursor=0;
        while(m.find()) {
            out.append(source,cursor,m.start());
            String token=prefix+(markers.size()+1)+"⟧";
            markers.add(token); originals.add(m.group()); out.append(token); cursor=m.end();
        }
        out.append(source,cursor,source.length());
        return new Protection(out.toString(),prefix,markers,originals);
    }
}