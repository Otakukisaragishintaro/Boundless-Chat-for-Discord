package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Credential redaction is applied before diagnostic records reach persistent storage. */
public class DiagnosticLogStoreTest {
    @Test
    public void defaultReadLimitIncludesEveryRetainedDiagnosticEntry() {
        assertEquals(DiagnosticLogStore.MAX_ENTRIES, DiagnosticLogStore.DEFAULT_READ_LIMIT);
    }

    @Test
    public void sanitizesBearerApiKeyAndProviderKeyForms() {
        String source = "Authorization: Bearer secret-token api_key=private-value sk-12345678901234567890";
        String result = DiagnosticLogStore.sanitizeMessage(source);
        assertTrue(result.contains("Authorization: Bearer [REDACTED]"));
        assertTrue(result.contains("api_key=[REDACTED]"));
        assertTrue(result.contains("[REDACTED_API_KEY]"));
        assertFalseContainsSecret(result, "secret-token");
        assertFalseContainsSecret(result, "private-value");
        assertFalseContainsSecret(result, "sk-12345678901234567890");
    }

    @Test
    public void boundsDiagnosticMessageLength() {
        String result = DiagnosticLogStore.sanitizeMessage("x".repeat(5000));
        assertEquals(4000 + "…[truncated]".length(), result.length());
        assertTrue(result.endsWith("…[truncated]"));
    }

    private static void assertFalseContainsSecret(String value, String secret) {
        if (value.contains(secret)) throw new AssertionError("Secret was not redacted");
    }
}
