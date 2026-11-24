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
    public void reorderedProtectedMarkersAreRejectedBeforeRestoration() throws Exception {
        DiscordTokenMarkers.Protection protection =
                DiscordTokenMarkers.protect("@everyone first @here second");
        String reordered = protection.text.replace("<keep1/>", "<TEMP/>")
                .replace("<keep2/>", "<keep1/>").replace("<TEMP/>", "<keep2/>");
        assertInvalid(() -> protection.restore(reordered));
    }

    private static void assertInvalid(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Invalid protected marker sequence was accepted");
        } catch (TranslationException expected) {
            assertEquals(TranslationException.Kind.PARSE, expected.kind());
            assertEquals(TranslationException.Reason.TRANSLATION_INVALID, expected.reason());
        }
    }

    private interface CheckedAction { void run() throws Exception; }
}
