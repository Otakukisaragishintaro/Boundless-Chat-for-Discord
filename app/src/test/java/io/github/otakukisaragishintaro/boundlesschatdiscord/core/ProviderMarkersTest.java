package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.*;
import org.junit.Test;

public class ProviderMarkersTest {
    @Test public void encodesNestedKeepStyleAndEmojiTokens() throws Exception {
        String source="Visit <x1>the site <keep1/> 😀 for details</x1> now";
        ProviderMarkers.Protection p=ProviderMarkers.protect(source);
        assertFalse(p.text.contains("<keep1/>")); assertFalse(p.text.contains("<x1>"));
        assertFalse(p.text.contains("😀")); assertEquals(source,p.restore(p.text));
        String translated=p.text.replace("Visit ","访问").replace("the site ","网站")
                .replace(" for details"," 获取详情").replace(" now"," 现在");
        assertEquals("访问 <x1>网站 <keep1/> 😀 获取详情</x1> 现在",p.restore(translated));
    }
    @Test public void rejectsMissingDuplicateReorderedAndDamagedTokens() {
        ProviderMarkers.Protection p=ProviderMarkers.protect("<keep1/> middle <x1>text</x1>");
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("⟦BCP[0-9]+_[0-9]+⟧").matcher(p.text);
        java.util.List<String> t=new java.util.ArrayList<>(); while(m.find())t.add(m.group());
        assertEquals(3,t.size());
        assertInvalid(p,"middle "+t.get(1)+"text"+t.get(2));
        assertInvalid(p,t.get(0)+t.get(0)+" middle "+t.get(1)+"text"+t.get(2));
        assertInvalid(p,t.get(2)+" middle "+t.get(1)+"text"+t.get(0));
        assertInvalid(p,p.text.replace("⟧","⟩"));
    }
    @Test public void avoidsSourceNamespaceCollision() throws Exception {
        String source="literal ⟦BCP0_1⟧ <keep1/>";
        ProviderMarkers.Protection p=ProviderMarkers.protect(source);
        assertTrue(p.text.contains("⟦BCP0_1⟧")); assertTrue(p.text.contains("⟦BCP1_1⟧"));
        assertEquals(source,p.restore(p.text));
    }
    @Test public void emoticonsAndIncompleteTagsArePlainText() throws Exception {
        String source=">.< <3 <.< >_< <keep1>";
        ProviderMarkers.Protection p=ProviderMarkers.protect(source);
        assertEquals(source,p.text); assertEquals(source,p.restore(p.text));
    }
    private static void assertInvalid(ProviderMarkers.Protection p,String value) {
        try { p.restore(value); fail("invalid placeholder sequence"); }
        catch(TranslationException expected) { assertEquals(TranslationException.Kind.PARSE,expected.kind()); }
    }
}