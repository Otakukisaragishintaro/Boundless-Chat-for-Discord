package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

public class ProtectionPromptConfigTest {
    @Test
    public void everySupportedLanguageHasItsOwnProtectionPromptPreview() {
        for (String language : LanguagePromptConfig.LANGUAGES) {
            String prompt = Protocols.protectionPromptFor(language);
            assertTrue(prompt.contains(language));
            assertTrue(prompt.contains("<keepN/>"));
            assertTrue(prompt.contains("exactly once"));
            assertTrue(prompt.contains("complete marker sequence"));
            assertTrue(prompt.contains("silently compare the translation"));
        }
    }

    @Test
    public void protectionPromptCanBeDisabledForEveryProtocolWithoutRemovingBoundaryRule()
            throws Exception {
        for (String protocol : new String[]{Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_OPENAI_RESPONSES, Config.PROTOCOL_ANTHROPIC_MESSAGES}) {
            String enabled = Protocols.requestBody(protocol, "source<keep1/>", "translate", "model",
                    false, true, "日本語");
            String disabled = Protocols.requestBody(protocol, "source", "translate", "model",
                    false, false, "日本語");
            String enabledSystem = systemPrompt(protocol, enabled);
            String disabledSystem = systemPrompt(protocol, disabled);
            assertTrue(enabledSystem.contains("opaque structural placeholder"));
            assertTrue(enabledSystem.contains("NEVER translate, quote, copy, or output this checklist"));
            assertTrue(enabledSystem.contains("<keepN/>"));
            assertFalse(disabledSystem.contains("opaque structural placeholder"));
            assertFalse(disabledSystem.contains("<keepN/>"));
            assertFalse(disabledSystem.contains("PRIVATE VALIDATION CHECKLIST"));
            assertTrue(disabledSystem.contains("Translate only the text between"));
        }
    }

    private static String systemPrompt(String protocol, String body) throws Exception {
        JSONObject request = new JSONObject(body);
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            return request.getString("instructions");
        }
        if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
            return request.getString("system");
        }
        JSONArray messages = request.getJSONArray("messages");
        return messages.getJSONObject(0).getString("content");
    }
}
