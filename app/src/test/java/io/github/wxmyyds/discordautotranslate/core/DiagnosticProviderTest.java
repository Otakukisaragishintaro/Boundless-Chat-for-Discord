package io.github.wxmyyds.discordautotranslate.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ensures the cross-process diagnostic channel accepts only fixed, content-free events. */
public class DiagnosticProviderTest {

    @Test
    public void acceptsHookLifecycleCheckpoints() {
        String[] events = {
                "module lifecycle entered",
                "Discord package lifecycle entered",
                "Application.attach bootstrap hook installed",
                "Discord Application.attach hook triggered",
                "Discord hook profile selected",
                "Discord required hooks installed and runtime activated",
                "Discord Activity.onResume hook triggered",
                "Discord Activity window-focus hook triggered",
                "Discord chat input Activity attach entered",
                "Discord chat input editor focused",
                "Discord chat input send control found",
                "Discord chat input send control not found",
                "Discord translation button attached",
                "Discord translation button attach failed"
        };
        for (String event : events) {
            assertTrue("Expected allow-listed event: " + event,
                    DiagnosticProvider.isAllowedDiagnostic(event));
        }
    }

    @Test
    public void rejectsArbitraryOrContentBearingMessages() {
        assertFalse(DiagnosticProvider.isAllowedDiagnostic(
                "Discord chat input editor focused: private message text"));
        assertFalse(DiagnosticProvider.isAllowedDiagnostic(
                "message body=hello world"));
        assertFalse(DiagnosticProvider.isAllowedDiagnostic(""));
        assertFalse(DiagnosticProvider.isAllowedDiagnostic(
                "Discord Application.onCreate reached; diagnostic transport fallback"));
        assertFalse(DiagnosticProvider.isAllowedDiagnostic(
                "Application.onCreate fallback failed errorClass=RuntimeException"));
    }
}
