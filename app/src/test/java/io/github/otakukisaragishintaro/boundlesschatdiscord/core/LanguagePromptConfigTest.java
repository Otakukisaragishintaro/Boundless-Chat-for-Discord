package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** All 22 supported languages have independent receive and send prompt slots. */
public class LanguagePromptConfigTest {
    @Test
    public void defaultLibraryCoversEverySupportedLanguageInBothDirections() {
        Map<String, String> defaults = LanguagePromptConfig.defaults();
        assertEquals(22, LanguagePromptConfig.LANGUAGES.size());
        assertEquals(22, defaults.size());
        for (String language : LanguagePromptConfig.LANGUAGES) {
            assertTrue(defaults.containsKey(language));
            assertTrue(defaults.get(language).contains("into " + language));
        }
        assertEquals(22, LanguagePromptConfig.read("", "English", null, false).size());
        assertEquals(22, LanguagePromptConfig.read("", "English", null, true).size());
    }

    @Test
    public void legacySinglePromptMigratesOnlyIntoItsSelectedLanguage() {
        Map<String, String> incoming = LanguagePromptConfig.read(
                "", "日本語", "Japanese-only custom prompt", false);
        assertEquals("Japanese-only custom prompt", incoming.get("日本語"));
        assertNotEquals("Japanese-only custom prompt", incoming.get("English"));
        assertTrue(incoming.get("English").contains("into English"));

        Map<String, String> outbound = LanguagePromptConfig.read(
                "", "English", "Send only into English", true);
        assertEquals("Send only into English", outbound.get("English"));
        assertNotEquals("Send only into English", outbound.get("日本語"));
    }

    @Test
    public void jsonRequiresAllTwentyTwoSupportedLanguagesAndPreservesExtraLegacyLanguage() {
        Map<String, String> values = new LinkedHashMap<>(LanguagePromptConfig.defaults());
        values.put("English", "English-specific receive prompt");
        values.put("Legacy Language", "Legacy language prompt");
        String json = LanguagePromptConfig.toJson(values);
        Map<String, String> parsed = LanguagePromptConfig.read(json, "English", null, false);
        assertEquals("English-specific receive prompt", parsed.get("English"));
        assertEquals("Legacy language prompt", parsed.get("Legacy Language"));
        try {
            JSONObject incomplete = new JSONObject(LanguagePromptConfig.toJson(values));
            incomplete.remove("English");
            assertTrue(Config.read(new MemoryPrefs().put(Config.KEY_INCOMING_PROMPTS,
                    incomplete.toString())).configError != null);
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }


    @Test
    public void configResolvesIncomingStyleFromChannelThenServerThenGlobal() {
        IncomingPromptStyles styles = IncomingPromptStyles.defaults().addStyle("Formal");
        String formal = styles.styles().get(1).id;
        styles = styles.withPrompt(formal, "English", "Formal English translation.");
        TranslationScope scope = new TranslationScope(true,
                java.util.Collections.singletonList(
                        new TranslationScope.Rule("10", "Server", true, "default")),
                java.util.Collections.singletonList(
                        new TranslationScope.Rule("20", "Channel", true, formal)));
        Config.Snapshot snapshot = Config.read(new MemoryPrefs()
                .put(Config.KEY_TARGET_LANG, "English")
                .put(Config.KEY_INCOMING_STYLES, styles.toJson())
                .put(Config.KEY_TRANSLATION_SCOPE, scope.toJson()));

        assertEquals("Formal English translation.",
                snapshot.forIncomingTranslation("10", "20").systemPrompt);
        assertEquals(styles.resolve("English", "default"),
                snapshot.forIncomingTranslation("10", "21").systemPrompt);
        assertEquals(styles.resolve("English", "default"),
                snapshot.forIncomingTranslation("11", "21").systemPrompt);
        assertNotEquals(snapshot.version(),
                snapshot.forIncomingTranslation("10", "20").version());
    }

    @Test
    public void configRejectsScopeRulesThatReferenceMissingPromptStyles() {
        TranslationScope scope = new TranslationScope(true,
                java.util.Collections.singletonList(
                        new TranslationScope.Rule("10", "Server", true, "missing-style")),
                java.util.Collections.emptyList());
        Config.Snapshot invalid = Config.read(new MemoryPrefs()
                .put(Config.KEY_INCOMING_STYLES, IncomingPromptStyles.defaults().toJson())
                .put(Config.KEY_TRANSLATION_SCOPE, scope.toJson()));
        org.junit.Assert.assertNotNull(invalid.configError);
    }

    @Test
    public void defaultSendTestUsesTheButtonMatchingTheSelectedLanguage() {
        Map<String, String> prompts = new LinkedHashMap<>(LanguagePromptConfig.defaults());
        prompts.put("English", "English send prompt");
        prompts.put("日本語", "Japanese send prompt");
        java.util.List<OutboundButtonConfig> buttons = java.util.Arrays.asList(
                new OutboundButtonConfig("en", "English", "English", Config.DEF_SEND_SYSTEM_PROMPT),
                new OutboundButtonConfig("ja", "Japanese", "日本語", Config.DEF_SEND_SYSTEM_PROMPT));
        Config.Snapshot snapshot = Config.read(new MemoryPrefs()
                .put(Config.KEY_SEND_TARGET_LANG, "日本語")
                .put(Config.KEY_OUTBOUND_BUTTONS, OutboundButtonConfig.toJson(buttons))
                .put(Config.KEY_OUTBOUND_PROMPTS, LanguagePromptConfig.toJson(prompts)));
        assertEquals("Japanese send prompt", snapshot.forSendTranslation().systemPrompt);
    }

    @Test
    public void sendTranslationUsesLanguagePromptUnlessButtonHasExplicitOverride() {
        Map<String, String> prompts = new LinkedHashMap<>(LanguagePromptConfig.defaults());
        prompts.put("日本語", "Japanese send prompt");
        Config.Snapshot snapshot = Config.read(new MemoryPrefs()
                .put(Config.KEY_OUTBOUND_PROMPTS, LanguagePromptConfig.toJson(prompts))
                .put(Config.KEY_SEND_TARGET_LANG, "日本語"));

        Config.Snapshot languageDefault = snapshot.forSendTranslation(
                new OutboundButtonConfig("default", "Japanese", "日本語",
                        Config.DEF_SEND_SYSTEM_PROMPT));
        assertEquals("Japanese send prompt", languageDefault.systemPrompt);

        Config.Snapshot explicitOverride = snapshot.forSendTranslation(
                new OutboundButtonConfig("custom", "Japanese custom", "日本語",
                        "Per-button custom prompt"));
        assertEquals("Per-button custom prompt", explicitOverride.systemPrompt);
    }
}
