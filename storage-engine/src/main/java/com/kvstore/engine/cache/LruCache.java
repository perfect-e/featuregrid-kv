package com.kvstore.engine.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Day 9: Thread-safe LRU (Least Recently Used) cache backed by {@link LinkedHashMap}
 * in access-order mode.
 *
 * <h2>Why LRU for a KV storage engine?</h2>
 * <pre>
 *   READ PATH (before LRU):
 *     GET "user:1" → check memtable (miss) → check bloom filter → scan SSTable (disk I/O)
 *
 *   READ PATH (after LRU):
 *     GET "user:1" → check LRU cache (HIT → return instantly, zero disk I/O)
 *
 *   "Hot" keys (frequently accessed) stay in cache.
 *   "Cold" keys are evicted to make room, so cache never exceeds maxSize.
 * </pre>
 *
 * <h2>LinkedHashMap in access-order mode</h2>
 * <pre>
 *   Normal insertion-order LinkedHashMap:  [A, B, C, D, E]  (oldest → newest insert)
 *   Access-order LinkedHashMap:
 *     after GET("C"):                      [A, B, D, E, C]  (C moved to tail)
 *     after GET("A"):                      [B, D, E, C, A]  (A moved to tail)
 *     When capacity exceeded:              EVICT head (B) — the least recently used
 * </pre>
 *
 * <p>Thread safety: all public methods are {@code synchronized}. This is correct for
 * a storage engine where disk I/O is the true bottleneck — lock contention on the cache
 * itself is never the limiting factor.
 *
 * @param <K> Key type
 * @param <V> Value type
 */
public class LruCache<K, V> {

    private final int maxSize;
    private final AtomicLong hits   = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /** The backing map — access-order, evicts eldest when over capacity. */
    private final LinkedHashMap<K, V> map;

    public LruCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("LRU maxSize must be > 0");
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<>(maxSize, 0.75f, /* accessOrder */ true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruCache.this.maxSize;
            }
        };
    }

    /** Returns cached value for {@code key}, or {@code null} on a miss. Updates access order. */
    public synchronized V get(K key) {
        V v = map.get(key);
        if (v != null) hits.incrementAndGet();
        else           misses.incrementAndGet();
        return v;
    }

    /** Inserts or updates {@code key → value}. May evict the eldest entry if at capacity. */
    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    /** Removes the entry for {@code key} (call on every write/delete to keep cache consistent). */
    public synchronized void invalidate(K key) {
        map.remove(key);
    }

    /** Removes all entries (call after a compaction that changes which SSTable owns a key). */
    public synchronized void clear() {
        map.clear();
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    public long hitCount()  { return hits.get(); }
    public long missCount() { return misses.get(); }
    public int  size()      { return map.size(); }
    public int  maxSize()   { return maxSize; }

    /**
     * Returns the cache hit percentage (0–100). Returns 0 if no lookups yet.
     */
    public int hitPercent() {
        long total = hits.get() + misses.get();
        if (total == 0) return 0;
        return (int) (hits.get() * 100L / total);
    }
}
