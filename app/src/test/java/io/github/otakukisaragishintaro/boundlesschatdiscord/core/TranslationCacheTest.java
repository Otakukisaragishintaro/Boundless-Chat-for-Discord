package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class TranslationCacheTest {
    @Test
    public void returnsStoredTranslationWithoutChangingItsValue() {
        TranslationCache cache = new TranslationCache(4);
        cache.put("same-source-and-config", "你好");
        assertEquals("你好", cache.get("same-source-and-config"));
    }

    @Test
    public void rejectsEmptyKeysAndTranslations() {
        TranslationCache cache = new TranslationCache(4);
        cache.put(null, "译文");
        cache.put("", "译文");
        cache.put("empty-result", "");
        cache.put("null-result", null);
        assertNull(cache.get("empty-result"));
        assertNull(cache.get("null-result"));
    }

    @Test
    public void removesRejectedTranslation() {
        TranslationCache cache = new TranslationCache(4);
        cache.put("key", "译文");
        cache.remove("key");
        assertNull(cache.get("key"));
    }

    @Test
    public void memoryLayerUsesLeastRecentlyUsedEviction() {
        TranslationCache cache = new TranslationCache(2);
        cache.put("first", "一");
        cache.put("second", "二");
        assertEquals("一", cache.get("first")); // Refresh first's LRU position.
        cache.put("third", "三");
        assertNull(cache.get("second"));
        assertEquals("一", cache.get("first"));
        assertEquals("三", cache.get("third"));
    }

    @Test
    public void clearRemovesAllMemoryEntries() {
        TranslationCache cache = new TranslationCache(4);
        cache.put("one", "一");
        cache.put("two", "二");
        cache.clear();
        assertNull(cache.get("one"));
        assertNull(cache.get("two"));
    }
}
