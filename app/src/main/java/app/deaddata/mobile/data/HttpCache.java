package app.deaddata.mobile.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Small in-memory TTL cache for API responses.
 *
 * Every entry carries its own expiry, so volatile endpoints (match history) and
 * stable ones (hero assets) can share one cache with different lifetimes. The
 * map is access-ordered and bounded, so a long session cannot grow it without
 * limit.
 */
public final class HttpCache {
    private static final int MAX_ENTRIES = 64;

    private final Map<String, CacheEntry> entries =
            new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };
    private final LongSupplier clock;

    public HttpCache() {
        this(System::currentTimeMillis);
    }

    /** Test seam: lets unit tests drive time instead of sleeping. */
    public HttpCache(LongSupplier clock) {
        this.clock = clock;
    }

    /** Cached body, or {@code null} when it was never stored or has expired. */
    public synchronized String get(String key) {
        if (key == null) return null;
        CacheEntry entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt <= clock.getAsLong()) {
            entries.remove(key);
            return null;
        }
        return entry.value;
    }

    public synchronized void put(String key, String value, long ttlMillis) {
        if (key == null || value == null) return;
        entries.put(key, new CacheEntry(value, clock.getAsLong() + Math.max(0L, ttlMillis)));
    }

    public synchronized void remove(String key) {
        entries.remove(key);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    private static final class CacheEntry {
        private final String value;
        private final long expiresAt;

        private CacheEntry(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
