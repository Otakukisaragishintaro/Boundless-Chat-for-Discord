package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class StructuredTranslationTest {
    private static String response(String... idAndText) throws Exception {
        JSONArray segments = new JSONArray();
        for (int i = 0; i < idAndText.length; i += 2) {
            JSONObject item = new JSONObject();
            item.put("id", idAndText[i]);
            item.put("text", idAndText[i + 1]);
            segments.put(item);
        }
        return new JSONObject().put("segments", segments).toString();
    }

    @Test
    public void restoresSourceOrderWhenProviderReordersSegmentIds() throws Exception {
        StructuredTranslation.Source source =
                StructuredTranslation.split("A<keep1/>B<keep2/>C");
        assertEquals("A2<keep1/>B2<keep2/>C2",
                source.restore(response("s2", "C2", "s0", "A2", "s1", "B2")));
    }

    @Test
    public void preservesEmptyLeadingTrailingAndAdjacentSegments() throws Exception {
        String sourceText = "<keep1/><keep2/>middle<keep3/>";
        StructuredTranslation.Source source = StructuredTranslation.split(sourceText);
        assertEquals("<keep1/><keep2/>middle<keep3/>",
                source.restore(response("s3", "", "s1", "", "s0", "", "s2", "middle")));
        assertTrue(StructuredTranslation.hasMarkers(sourceText));
        assertFalse(StructuredTranslation.hasMarkers("ordinary text <3 >.<"));
    }

    @Test
    public void rejectsMissingDuplicateUnknownIdsAndNonStringText() throws Exception {
        StructuredTranslation.Source source = StructuredTranslation.split("A<keep1/>B");
        for (String invalid : new String[]{
                response("s0", "A"),
                response("s0", "A", "s0", "again"),
                response("s0", "A", "unknown", "B"),
                "{\"segments\":[{\"id\":\"s0\",\"text\":12},{\"id\":\"s1\",\"text\":\"B\"}]}"
        }) {
            try {
                source.restore(invalid);
                fail("Malformed segment response must be rejected");
            } catch (TranslationException expected) {
                assertEquals(TranslationException.Kind.PARSE, expected.kind());
            }
        }
    }
}
