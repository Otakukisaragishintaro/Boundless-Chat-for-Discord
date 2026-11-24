package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class IncomingPromptStylesTest {
    @Test
    public void legacyPerLanguagePromptsBecomeTheDefaultStyleWithoutLoss() {
        Map<String, String> legacy = new LinkedHashMap<>(LanguagePromptConfig.defaults());
        legacy.put("English", "Legacy English receive prompt");
        IncomingPromptStyles styles = IncomingPromptStyles.read("", legacy);

        assertEquals(1, styles.styles().size());
        assertEquals("default", styles.defaultStyleId("English"));
        assertEquals("Legacy English receive prompt", styles.resolve("English", null));
        assertNotEquals("Legacy English receive prompt", styles.resolve("日本語", null));
    }

    @Test
    public void stylesContainIndependentPromptsForAllTwentyTwoLanguages() {
        IncomingPromptStyles styles = IncomingPromptStyles.defaults().addStyle("Casual");
        IncomingPromptStyles.Style casual = styles.styles().get(1);
        assertEquals(22, casual.prompts.size());
        styles = styles.withPrompt(casual.id, "English", "Use casual English.");
        styles = styles.withPrompt(casual.id, "日本語", "自然な日本語で訳す。");

        assertEquals("Use casual English.", styles.resolve("English", casual.id));
        assertEquals("自然な日本語で訳す。", styles.resolve("日本語", casual.id));
        assertTrue(styles.resolve("Français", casual.id).contains("Français"));
    }

    @Test
    public void defaultsAreIndependentPerLanguageAndSurviveSerialization() {
        IncomingPromptStyles styles = IncomingPromptStyles.defaults().addStyle("Formal");
        String formal = styles.styles().get(1).id;
        styles = styles.withPrompt(formal, "English", "Formal English.");
        styles = styles.setDefault("English", formal);

        IncomingPromptStyles parsed = IncomingPromptStyles.read(styles.toJson(), null);
        assertEquals(formal, parsed.defaultStyleId("English"));
        assertEquals("Formal English.", parsed.resolve("English", null));
        assertEquals("default", parsed.defaultStyleId("日本語"));
        assertEquals(styles.toJson(), parsed.toJson());
    }

    @Test
    public void cannotDeleteTheLastStyleOrAStyleStillUsedAsAnyLanguageDefault() {
        IncomingPromptStyles styles = IncomingPromptStyles.defaults();
        assertThrows(IllegalArgumentException.class,
                () -> styles.deleteStyle(IncomingPromptStyles.DEFAULT_STYLE_ID));
        IncomingPromptStyles two = styles.addStyle("Second");
        String second = two.styles().get(1).id;
        assertThrows(IllegalArgumentException.class, () -> two.deleteStyle("default"));
        assertThrows(IllegalArgumentException.class, () -> two.renameStyle("missing", "New"));
        assertEquals(2, two.styles().size());
        assertTrue(second.matches("[A-Za-z0-9_-]{1,64}"));
    }

    @Test
    public void legacyCustomLanguageLabelsKeepThePreviousGeneratedDefaultBehavior() {
        IncomingPromptStyles styles = IncomingPromptStyles.defaults();
        assertEquals(LanguagePromptConfig.defaultPrompt("Simplified Chinese"),
                styles.resolve("Simplified Chinese", null));
        styles = styles.addStyle("Alternate");
        String alternate = styles.styles().get(1).id;
        assertEquals(LanguagePromptConfig.defaultPrompt("Simplified Chinese"),
                styles.resolve("Simplified Chinese", alternate));
    }

    @Test
    public void malformedOrIncompleteStyleLibrariesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> IncomingPromptStyles.read("{", null));
        assertThrows(IllegalArgumentException.class,
                () -> IncomingPromptStyles.defaults().withPrompt("default", "English", " "));
        assertThrows(IllegalArgumentException.class,
                () -> IncomingPromptStyles.defaults().setDefault("English", "missing"));
    }
}
