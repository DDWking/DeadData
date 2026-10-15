package app.deaddata.mobile.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Covers the TTL and eviction rules of the in-memory API response cache. */
public class HttpCacheTest {
    private long now = 1000L;
    private final HttpCache cache = new HttpCache(() -> now);

    @Test
    public void servesAFreshEntry() {
        cache.put("a", "body", 500L);

        assertEquals("body", cache.get("a"));
    }

    @Test
    public void missingKeysAndNullKeysAreEmpty() {
        assertNull(cache.get("missing"));
        assertNull(cache.get(null));
    }

    @Test
    public void entryExpiresOnceItsTtlHasPassed() {
        cache.put("a", "body", 500L);

        now += 499L;
        assertEquals("body", cache.get("a"));

        now += 1L;
        assertNull(cache.get("a"));
        assertEquals(0, cache.size());
    }

    @Test
    public void rewritingAnEntryRestartsItsTtl() {
        cache.put("a", "first", 500L);
        now += 400L;
        cache.put("a", "second", 500L);

        now += 400L;
        assertEquals("second", cache.get("a"));
    }

    @Test
    public void zeroTtlExpiresImmediatelyButStaysReadableInItsOwnInstant() {
        cache.put("a", "body", 0L);
        now += 1L;

        assertNull(cache.get("a"));
    }

    @Test
    public void nullKeysAndValuesAreIgnored() {
        cache.put(null, "body", 500L);
        cache.put("a", null, 500L);

        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
    }

    @Test
    public void removeAndClearDropEntries() {
        cache.put("a", "one", 500L);
        cache.put("b", "two", 500L);

        cache.remove("a");
        assertNull(cache.get("a"));
        assertEquals("two", cache.get("b"));

        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    public void theOldestUnusedEntryIsEvictedBeyondCapacity() {
        for (int i = 0; i < 64; i++) cache.put("k" + i, "v" + i, 10_000L);
        assertEquals("v0", cache.get("k0"));

        cache.put("overflow", "v", 10_000L);

        assertEquals(64, cache.size());
        assertNull(cache.get("k1"));
        assertEquals("v0", cache.get("k0"));
    }
}
