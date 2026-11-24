package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectionPromptConfigTest {
    @Test
    public void everySupportedLanguageHasItsOwnProtectionPromptPreview() {
        for (String language : LanguagePromptConfig.LANGUAGES) {
            String prompt = Protocols.protectionPromptFor(language);
            assertTrue(prompt.contains(language));
            assertTrue(prompt.contains("<keepN/>"));
            assertTrue(prompt.contains("exactly once"));
            assertTrue(prompt.contains("same order"));
        }
    }

    @Test
    public void protectionPromptCanBeDisabledWithoutRemovingWrapperBoundaryRule() throws Exception {
        String enabled = Protocols.requestBody(Config.PROTOCOL_OPENAI_COMPATIBLE,
                "source", "translate", "model", false, true, "日本語");
        String disabled = Protocols.requestBody(Config.PROTOCOL_OPENAI_COMPATIBLE,
                "source", "translate", "model", false, false, "日本語");
        assertTrue(enabled.contains("protected marker"));
        assertTrue(enabled.contains("<keepN/>"));
        assertFalse(disabled.contains("protected marker"));
        assertFalse(disabled.contains("<keepN/>"));
        assertTrue(disabled.contains("Translate only the text between"));
    }
}
