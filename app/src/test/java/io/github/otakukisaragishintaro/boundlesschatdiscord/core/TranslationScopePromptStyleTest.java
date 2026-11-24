package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TranslationScopePromptStyleTest {
    @Test
    public void styleResolutionUsesChannelThenServerThenGlobalInheritance() {
        TranslationScope scope = new TranslationScope(true,
                Collections.singletonList(new TranslationScope.Rule("10", "Server", true, "formal")),
                Arrays.asList(
                        new TranslationScope.Rule("20", "Inherit server", true),
                        new TranslationScope.Rule("21", "Channel override", true, "casual")));

        assertEquals("formal", scope.promptStyleId("10", "20"));
        assertEquals("casual", scope.promptStyleId("10", "21"));
        assertEquals("formal", scope.promptStyleId("10", "22"));
        assertNull(scope.promptStyleId("11", "22"));
        assertEquals("casual", scope.promptStyleId("11", "21"));
    }

    @Test
    public void styleOverridesRoundTripAndAffectMatchingPolicy() {
        TranslationScope original = new TranslationScope(true,
                Collections.singletonList(new TranslationScope.Rule("10", "Server", true, "formal")),
                Collections.singletonList(new TranslationScope.Rule("20", "Channel", true, "casual")));
        TranslationScope parsed = TranslationScope.parse(original.toJson());
        assertEquals(original, parsed);
        assertEquals(original.toJson(), parsed.toJson());

        TranslationScope changed = new TranslationScope(true,
                Collections.singletonList(new TranslationScope.Rule("10", "Server", true, "casual")),
                original.channels);
        assertNotEquals(original, changed);
        org.junit.Assert.assertFalse(original.sameMatchingPolicy(changed));
    }

    @Test
    public void legacyRulesWithoutStyleOverrideRemainValidAndCompact() {
        String legacy = "{\"defaultTranslate\":true,\"servers\":["
                + "{\"id\":\"10\",\"label\":\"Server\",\"translate\":true}],\"channels\":[]}";
        TranslationScope parsed = TranslationScope.parse(legacy);
        assertNull(parsed.servers.get(0).promptStyleId);
        assertEquals(legacy, parsed.toJson());
    }
}
