package com.kvstore.engine.lsm;

import com.kvstore.engine.ValueEntry;
import org.slf4j.Logger;                             // simple logging facade for java
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * <h2>Why a skip list instead of a HashMap?</h2>
 * <p>SSTables store data <em>sorted by key</em>. When the memtable is flushed to an
 * SSTable, we need to iterate its entries in ascending key order — that's a free
 * operation on a skip list ({@code O(1)} to start the iterator) but costs
 * {@code O(n log n)} if we had to sort a HashMap.
 *
 * <p>Additionally, {@link ConcurrentSkipListMap} is lock-free for reads and uses
 * fine-grained locking for writes, making it far more concurrent than a
 * {@code Collections.synchronizedTreeMap}.
 *
 * <h2>Size tracking</h2>
 * <p>We track memory usage in bytes (key + value) via an {@link AtomicLong}.
 * When {@link #isFull()} returns {@code true}, {@link com.kvstore.engine.LsmStorageEngine}
 * triggers a flush to an immutable SSTable file and resets to a new empty memtable.
 *
 * <h2>Tombstones</h2>
 * <p>DELETE operations write a tombstone ({@code ValueEntry.tombstone=true}) into
 * the memtable rather than removing the key. This is essential: a delete must
 * shadow older versions in SSTables. The tombstone is preserved through the flush
 * and only evicted during compaction once it's confirmed no older version exists.
 */
public class SkipListMemtable {

    private static final Logger log = LoggerFactory.getLogger(SkipListMemtable.class);

    /** Default memtable capacity: 4 MB of key + value bytes. */
    public static final long DEFAULT_MAX_SIZE_BYTES = 4L * 1024 * 1024;

    private final ConcurrentSkipListMap<String, ValueEntry> data = new ConcurrentSkipListMap<>();
    private final AtomicLong sizeBytes = new AtomicLong(0);
    private final long maxSizeBytes;

    public SkipListMemtable() {
        this(DEFAULT_MAX_SIZE_BYTES);
    }

    public SkipListMemtable(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    // ─── Write ───────────────────────────────────────────────────────────────

    /**
     * Upserts a key with the given {@link ValueEntry}.
     *
     * <p>If the key already exists, the old entry's size is subtracted before
     * the new entry's size is added — so {@link #sizeBytes} always reflects
     * the net current memory footprint.
     */
    public void put(String key, ValueEntry entry) {
        ValueEntry previous = data.put(key, entry);
        long delta = entrySize(key, entry);
        if (previous != null) {
            delta -= entrySize(key, previous);
        }
        sizeBytes.addAndGet(delta);
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    /**
     * Returns the entry for {@code key}, or empty if not present.
     * <p>Callers must check {@link ValueEntry#tombstone()} to distinguish a
     * "deleted" entry from a missing key.
     */
    public Optional<ValueEntry> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    // ─── Size queries ─────────────────────────────────────────────────────────

    /** Total bytes currently occupied by all keys and values (including tombstones). */
    public long sizeBytes() {
        return sizeBytes.get();
    }

    /** Total number of entries, including tombstones. */
    public int entryCount() {
        return data.size();
    }

    /** Number of live (non-tombstone) entries. */
    public long liveCount() {
        return data.values().stream().filter(e -> !e.tombstone()).count();
    }

    /**
     * Returns {@code true} when the memtable has reached or exceeded its byte
     * capacity and should be flushed to an SSTable.
     */
    public boolean isFull() {
        return sizeBytes.get() >= maxSizeBytes;
    }

    /** Fill percentage (0–100) for metrics and UI display. */
    public int fillPercent() {
        return (int) Math.min(100, sizeBytes.get() * 100L / maxSizeBytes);
    }

    // ─── Flush support ────────────────────────────────────────────────────────

    /**
     * Returns an ascending, sorted, <em>snapshot</em> view of the memtable.
     *
     * <p>This is the iterator handed to {@link com.kvstore.engine.sstable.SSTableWriter}
     * during a flush. The snapshot is a point-in-time copy — new writes to the
     * live memtable don't affect it.
     *
     * <p>In production systems this is typically done by atomically swapping to a
     * new mutable memtable while flushing the old one on a background thread.
     * For correctness and simplicity we produce a snapshot here.
     */
    public NavigableMap<String, ValueEntry> snapshot() {
        return new java.util.TreeMap<>(data);   // sorted copy
    }

    /** Clears all entries and resets the size counter. Called after a successful flush. */
    public void clear() {
        data.clear();
        sizeBytes.set(0);
        log.debug("Memtable cleared after flush.");
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private static long entrySize(String key, ValueEntry entry) {
        long keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        long valBytes = (entry.value() != null) ? entry.value().length : 0;
        return keyBytes + valBytes + 16L; // 16 bytes for object overhead + version + flags
    }
}
