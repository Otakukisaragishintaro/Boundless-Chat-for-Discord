package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Program-owned Discord tokens are never silently dropped, even if optional validation is off. */
public class DiscordTokenMarkersTest {
    @Test
    public void restoresProtectedMentionAndUrlExactly() throws Exception {
        String source = "@everyone visit https://example.invalid/docs";
        DiscordTokenMarkers.Protection protection = DiscordTokenMarkers.protect(source);
        assertTrue(protection.tokenCount() >= 2);
        assertEquals(source, protection.restore(protection.text));
    }

    @Test
    public void missingProtectedMarkerIsRejectedBeforeRestoration() throws Exception {
        DiscordTokenMarkers.Protection protection = DiscordTokenMarkers.protect("@everyone hello");
        assertInvalid(() -> protection.restore("hello"));
    }

    @Test
    public void duplicatedProtectedMarkerIsRejectedBeforeRestoration() throws Exception {
        DiscordTokenMarkers.Protection protection = DiscordTokenMarkers.protect("@everyone hello");
        assertInvalid(() -> protection.restore(protection.text + " " + protection.text));
    }

    @Test
    public void reorderedProtectedMarkersAreRestoredByIdentity() throws Exception {
        DiscordTokenMarkers.Protection protection =
                DiscordTokenMarkers.protect("@everyone first @here second");
        String reordered = protection.text
                .replace("⟦BC_TOKEN_1⟧", "⟦TEMP_TOKEN⟧")
                .replace("⟦BC_TOKEN_2⟧", "⟦BC_TOKEN_1⟧")
                .replace("⟦TEMP_TOKEN⟧", "⟦BC_TOKEN_2⟧");
        assertEquals("@here first @everyone second", protection.restore(reordered));
    }

    @Test
    public void unknownProtectedPlaceholderIsRejected() throws Exception {
        DiscordTokenMarkers.Protection protection = DiscordTokenMarkers.protect("@everyone hello");
        assertInvalid(() -> protection.restore(protection.text + " ⟦BC_TOKEN_99⟧"));
    }

    private static void assertInvalid(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Invalid protected placeholder set was accepted");
        } catch (TranslationException expected) {
            assertEquals(TranslationException.Kind.PARSE, expected.kind());
            assertEquals(TranslationException.Reason.TRANSLATION_INVALID, expected.reason());
        }
    }

    private interface CheckedAction { void run() throws Exception; }
}
