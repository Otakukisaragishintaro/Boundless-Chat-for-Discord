package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

public class StateStoreTest {
    private final StateStore store = StateStore.get();
    @Before public void reset() { store.onNewGeneration(); }

    @Test public void cacheCommitWaitsForOuterRenderAndIsIdempotent() {
        StateStore.Entry entry = store.obtain("row", "hash");
        TranslationTaskId task = store.begin(entry);
        assertTrue(store.complete(task, true, "译文", null));

        assertTrue(store.markCachePending("row", "译文"));
        java.util.List<StateStore.Entry> first = store.claimCacheCommitsForRow("row");
        assertEquals(1, first.size());
        assertSame("outer render completion claims the current entry", entry, first.get(0));
        assertTrue("a duplicate outer render must not claim a second write",
                store.claimCacheCommitsForRow("row").isEmpty());
    }

    @Test public void outerRowRenderClaimsPendingEmbedAndPollKeys() {
        StateStore.Entry embed = translated("12345678901234567\u0000embed\u0000card\u0000hash");
        StateStore.Entry poll = translated("12345678901234567\u0000poll\u0000question\u0000hash");
        assertTrue(store.markCachePending(embed.messageId, embed.translatedText));
        assertTrue(store.markCachePending(poll.messageId, poll.translatedText));
        assertEquals(2, store.claimCacheCommitsForRow("12345678901234567").size());
    }

    private StateStore.Entry translated(String id) {
        StateStore.Entry entry = store.obtain(id, "hash-" + id);
        TranslationTaskId task = store.begin(entry);
        assertTrue(store.complete(task, true, "译文", null));
        return entry;
    }

    @Test public void changedContentCannotInheritPendingCacheCommit() {
        StateStore.Entry old = store.obtain("row", "old");
        TranslationTaskId task = store.begin(old);
        assertTrue(store.complete(task, true, "旧译文", null));
        assertTrue(store.markCachePending("row", "旧译文"));

        StateStore.Entry current = store.obtain("row", "new");
        assertNotSame(old, current);
        assertTrue(store.claimCacheCommitsForRow("row").isEmpty());
    }

    @Test public void rejectedOldContentCannotChangeNewAttempt() {
        StateStore.Entry old = store.obtain("row", "h1");
        TranslationTaskId a = store.begin(old);
        StateStore.Entry edited = store.obtain("row", "h2");
        TranslationTaskId b = store.begin(edited);
        assertNotSame(old, edited);
        assertFalse(store.complete(a, false, null, null));
        assertEquals(StateStore.Status.TRANSLATING, edited.status);
        assertTrue(store.owns(b));
        assertTrue(store.complete(b, true, "译文", null));
        assertEquals("译文", edited.translatedText);
    }

    @Test public void sameContentRetryHasDistinctAttemptOwnership() {
        StateStore.Entry entry = store.obtain("row", "hash");
        TranslationTaskId a = store.begin(entry);
        assertTrue(store.complete(a, true, null, "failed"));
        entry.status = StateStore.Status.IDLE;
        TranslationTaskId b = store.begin(entry);
        assertNotEquals(a, b);
        assertFalse(store.complete(a, false, null, null));
        assertEquals(StateStore.Status.TRANSLATING, entry.status);
        assertTrue(store.complete(b, true, "ok", null));
    }

    @Test public void newGenerationRejectsOldAttemptEvenWithIdenticalHash() {
        TranslationTaskId old = store.begin(store.obtain("row", "hash"));
        store.onNewGeneration();
        StateStore.Entry current = store.obtain("row", "hash");
        TranslationTaskId task = store.begin(current);
        assertFalse(store.complete(old, false, null, null));
        assertTrue(store.owns(task));
        assertEquals(StateStore.Status.TRANSLATING, current.status);
    }

    @Test public void everyStatusIsBoundedAndSourceEvictsWithRecord() {
        for (int i = 0; i < 512; i++) {
            StateStore.Entry entry = store.obtain("row" + i, "h" + i);
            entry.status = StateStore.Status.TRANSLATED;
            store.rememberSource(entry.messageId, new StateStore.Source(new Object(), false, null));
        }
        assertEquals(512, store.size());
        assertNotNull(store.peek("row0")); // LRU touch protects this record.
        store.obtain("extra", "hash");
        assertEquals(512, store.size());
        assertNotNull(store.source("row0"));
        assertNull(store.peek("row1"));
        assertNull(store.source("row1"));
        for (int i = 0; i < 600; i++) store.begin(store.obtain("pending" + i, "h"));
        assertEquals(512, store.size());
    }

    @Test public void evictedAndRecreatedRecordRejectsEvenSameVersionAttempt() {
        TranslationTaskId old = store.begin(store.obtain("row", "h"));
        for (int i = 0; i < 512; i++) store.obtain("other" + i, "h");
        StateStore.Entry rebound = store.obtain("row", "h");
        TranslationTaskId next = store.begin(rebound);
        assertFalse(store.complete(old, true, "old", null));
        assertTrue(store.owns(next));
        assertNull(rebound.translatedText);
    }

    @Test public void ownershipSweepCannotPromoteOldPendingWorkOverNewAdmission() {
        java.util.List<TranslationTaskId> old = new java.util.ArrayList<>();
        for (int i = 0; i < 512; i++) old.add(store.begin(store.obtain("row" + i, "h")));
        TranslationTaskId newest = store.begin(store.obtain("newest", "h"));
        for (TranslationTaskId task : old) store.owns(task);
        store.obtain("next", "h");
        assertTrue(store.owns(newest));
        assertNull(store.peek("row1"));
        assertNotNull(store.peek("newest"));
    }

    @Test public void disabledCurrentAttemptCanReturnIdleWithoutTouchingOriginalChoice() {
        StateStore.Entry entry = store.obtain("row", "h");
        TranslationTaskId task = store.begin(entry);
        entry.showingOriginal = true;
        assertTrue(store.complete(task, false, null, null));
        assertEquals(StateStore.Status.IDLE, entry.status);
        assertTrue(entry.showingOriginal);
        assertFalse(store.owns(task));
    }
}
