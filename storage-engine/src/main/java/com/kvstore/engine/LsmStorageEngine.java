package com.kvstore.engine;

import com.kvstore.engine.cache.LruCache;
import com.kvstore.engine.compaction.CompactionManager;
import com.kvstore.engine.ttl.TtlReaper;
import com.kvstore.engine.lsm.SkipListMemtable;
import com.kvstore.engine.sstable.SSTableMetadata;
import com.kvstore.engine.sstable.SSTableReader;
import com.kvstore.engine.sstable.SSTableWriter;
import com.kvstore.engine.wal.WalEntry;
import com.kvstore.engine.wal.WalReader;
import com.kvstore.engine.wal.WalWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Day 5–6: Full LSM (Log-Structured Merge-Tree) storage engine.
 *
 * <h2>Architecture</h2>
 * <pre>
 * WRITE PATH:
 *   put/delete
 *     │
 *     ├──► WAL  (fsync — durable first)
 *     │
 *     └──► SkipListMemtable  (mutable, in-memory)
 *               │
 *               │ when isFull() == true
 *               ▼
 *           SSTableWriter.write() → new immutable .sst file on disk
 *           new empty SkipListMemtable replaces the old one
 *           new WAL segment replaces the old one (old WAL deleted)
 *
 * READ PATH:
 *   get(key)
 *     │
 *     ├──► 1. SkipListMemtable   (most recent, no disk I/O)
 *     │
 *     └──► 2. SSTables, newest → oldest
 *               - key-range pre-filter (SSTableMetadata.mightContain)
 *               - binary search sparse index
 *               - linear scan data block
 *               Returns first non-tombstone match; tombstone means "deleted".
 * </pre>
 *
 * <h2>Concurrency model</h2>
 * <p>A {@link ReentrantReadWriteLock} guards the memtable + SSTable list:
 * <ul>
 *   <li>Multiple concurrent readers (GET) hold the read lock simultaneously.</li>
 *   <li>Writes (PUT/DELETE) hold the write lock only for the duration of the
 *       memtable update (nanoseconds), not for the WAL fsync.</li>
 *   <li>Flush holds the write lock only to atomically swap the old memtable for
 *       a new empty one, then releases it before the slow SSTableWriter.write() call.</li>
 * </ul>
 *
 * <h2>Crash recovery</h2>
 * <ol>
 *   <li>Load all existing SSTable files from the data directory (newest first).</li>
 *   <li>Replay the WAL into a fresh SkipListMemtable.</li>
 *   <li>Open WalWriter (append mode) for new writes.</li>
 * </ol>
 *
 * <h2>Week 2 evolution</h2>
 * <p>Background compaction will merge SSTables, evict tombstones, and truncate
 * the WAL after each successful flush. This class exposes the hooks ({@link #sstables}
 * and {@link #forceFlush()}) that the compaction thread will call.
 */
public class LsmStorageEngine implements StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(LsmStorageEngine.class);

    private final Path dataDir;
    private final long memtableMaxBytes;

    /** Mutable, sorted in-memory buffer. Guarded by {@link #lock}. */
    private volatile SkipListMemtable memtable;

    /**
     * Immutable SSTable readers, ordered newest → oldest.
     * {@link CopyOnWriteArrayList} allows concurrent reads without locking,
     * while flush (which modifies the list) holds the write lock.
     */
    private final CopyOnWriteArrayList<SSTableReader> sstables = new CopyOnWriteArrayList<>();

    private WalWriter walWriter;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean closed = false;

    /** Background compaction thread — merges SSTables, evicts tombstones. */
    private final CompactionManager compactionManager;

    /**
     * Read-through LRU cache. Sits in front of bloom filter + SSTable scan.
     * Invalidated on every write/delete to maintain consistency.
     * Default capacity: 1000 entries — ~few MB in-memory for typical values.
     */
    private final LruCache<String, ValueEntry> readCache = new LruCache<>(1_000);

    /** Background TTL reaper — evicts expired keys from memtable every 10s. */
    private TtlReaper ttlReaper;

    // ─── Construction / Recovery ──────────────────────────────────────────────

    /**
     * Constructs and recovers the LSM engine from the given data directory.
     *
     * @param dataDir         where WAL and SSTable files are stored.
     * @param memtableMaxBytes byte capacity before a flush is triggered.
     */
    public LsmStorageEngine(Path dataDir, long memtableMaxBytes) {
        this.dataDir = dataDir;
        this.memtableMaxBytes = memtableMaxBytes;

        try {
            Files.createDirectories(dataDir);
            log.info("LsmStorageEngine starting. dataDir={}, memtable={}MB",
                    dataDir.toAbsolutePath(), memtableMaxBytes / (1024 * 1024));

            // 1. Load existing SSTables (newest first)
            loadSSTables();

            // 2. Replay WAL into a fresh memtable
            this.memtable = new SkipListMemtable(memtableMaxBytes);
            int recovered = replayWal();
            log.info("Recovery complete. {} SSTable(s) loaded, {} WAL entries replayed. Live keys: {}",
                    sstables.size(), recovered, size());

            // 3. Open WAL for new writes
            this.walWriter = new WalWriter(walPath());

            // 4. Start background compaction
            this.compactionManager = new CompactionManager(
                    dataDir, lock, sstables, updatedList -> {
                        // after compaction, clear cache — merged SSTable changes key ownership
                        readCache.clear();
                    });
            compactionManager.start();

            // 5. Start background TTL reaper
            this.ttlReaper = new TtlReaper(this);
            this.ttlReaper.start();

        } catch (IOException e) {
            throw new StorageException("LsmStorageEngine init failed at: " + dataDir, e);
        }
    }

    /** Convenience constructor using the default 4 MB memtable threshold. */
    public LsmStorageEngine(Path dataDir) {
        this(dataDir, SkipListMemtable.DEFAULT_MAX_SIZE_BYTES);
    }

    // ─── Write operations ─────────────────────────────────────────────────────

    @Override
    public void put(String key, byte[] value) {
        put(key, value, 0L);
    }

    /**
     * Stores a key-value pair with an optional TTL.
     *
     * @param key    the key (must not be blank)
     * @param value  the value bytes (must not be null)
     * @param ttlMs  time-to-live in ms; 0 means no expiry
     */
    public void put(String key, byte[] value, long ttlMs) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Key must not be blank");
        if (value == null) throw new IllegalArgumentException("Value must not be null");

        // Invalidate cache BEFORE WAL write so concurrent readers don't see a stale value
        readCache.invalidate(key);

        // WAL first (fsync before memory) — holds no lock during I/O
        walWriter.append(WalEntry.put(key, value));

        lock.writeLock().lock();
        try {
            memtable.put(key, buildEntry(value, false, ttlMs));
            if (memtable.isFull()) {
                flushUnderLock();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Key must not be blank");

        readCache.invalidate(key);

        walWriter.append(WalEntry.delete(key));

        lock.writeLock().lock();
        try {
            memtable.put(key, buildEntry(null, true));   // tombstone
            if (memtable.isFull()) {
                flushUnderLock();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ─── Read operations ──────────────────────────────────────────────────────

    @Override
    public Optional<ValueEntry> get(String key) {
        // 0. LRU Cache fast-path — zero disk I/O for hot keys
        ValueEntry cached = readCache.get(key);
        if (cached != null) {
            // Lazy TTL check on cached entry
            if (cached.isExpired()) {
                readCache.invalidate(key);
                return Optional.empty();
            }
            return cached.tombstone() ? Optional.empty() : Optional.of(cached);
        }

        lock.readLock().lock();
        try {
            // 1. Check memtable first (most recent data, no disk I/O)
            Optional<ValueEntry> memResult = memtable.get(key);
            if (memResult.isPresent()) {
                ValueEntry e = memResult.get();
                // Lazy TTL expiry: treat expired entries as if they don't exist
                if (e.isExpired()) return Optional.empty();
                if (!e.tombstone()) readCache.put(key, e);
                return e.tombstone() ? Optional.empty() : Optional.of(e);
            }

            // 2. Search SSTables newest → oldest (bloom filter checked inside reader)
            for (SSTableReader reader : sstables) {
                Optional<ValueEntry> sstResult = reader.get(key);
                if (sstResult.isPresent()) {
                    ValueEntry e = sstResult.get();
                    if (e.isExpired()) return Optional.empty();
                    if (!e.tombstone()) readCache.put(key, e); // populate cache from disk
                    return e.tombstone() ? Optional.empty() : Optional.of(e);
                }
            }
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long size() {
        lock.readLock().lock();
        try {
            return memtable.liveCount();
            // Note: SSTables may contain additional keys not yet superseded by memtable.
            // Full accurate count requires merging — too expensive for a hot-path call.
        } finally {
            lock.readLock().unlock();
        }
    }

    // ─── Flush ────────────────────────────────────────────────────────────────

    /**
     * Forces an immediate flush of the current memtable to a new SSTable.
     * Useful for tests, shutdown, and future compaction triggers.
     */
    public void forceFlush() {
        lock.writeLock().lock();
        try {
            if (memtable.entryCount() > 0) {
                flushUnderLock();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Performs the flush. <b>Must be called while holding the write lock.</b>
     *
     * <ol>
     *   <li>Snapshot the current memtable.</li>
     *   <li>Replace memtable with a new empty one.</li>
     *   <li>Release write lock, then write SSTable (slow I/O outside the lock).</li>
     * </ol>
     *
     * <p>If the SSTable write fails, the data is NOT lost — it is still in the WAL.
     * On the next restart, the WAL will be replayed and the memtable rebuilt.
     */
    private void flushUnderLock() {
        log.info("Memtable full ({}% — {} bytes). Flushing to SSTable...",
                memtable.fillPercent(), memtable.sizeBytes());

        // Snapshot + swap memtable (fast, under lock)
        var snapshot = memtable.snapshot();
        memtable = new SkipListMemtable(memtableMaxBytes);

        // Write SSTable outside the lock (slow disk I/O)
        lock.writeLock().unlock();
        SSTableMetadata meta;
        try {
            meta = SSTableWriter.write(dataDir, snapshot);
        } finally {
            lock.writeLock().lock(); // re-acquire before returning to caller
        }

        // Prepend new SSTable (newest first)
        SSTableReader reader = new SSTableReader(meta);
        sstables.add(0, reader);

        // Rotate WAL: delete old WAL, open a fresh one
        rotateWal();

        log.info("Flush complete. SSTable list size: {}", sstables.size());
    }

    // ─── Metrics exposed for UI ───────────────────────────────────────────────────────────────────────

    public int memtableFillPercent() {
        return memtable.fillPercent();
    }

    public int sstableCount() {
        return sstables.size();
    }

    public long walSizeBytes() {
        try { return Files.size(walPath()); } catch (IOException e) { return -1; }
    }

    /** Manually triggers a compaction cycle. Used by the dashboard and integration tests. */
    public void triggerCompaction() {
        compactionManager.triggerNow();
    }

    // ─── Cache metrics ────────────────────────────────────────────────────────
    public int  cacheHitPercent() { return readCache.hitPercent(); }
    public long cacheHitCount()   { return readCache.hitCount(); }
    public long cacheMissCount()  { return readCache.missCount(); }
    public int  cacheSize()       { return readCache.size(); }
    
    // ─── Debug Dump for UI ────────────────────────────────────────────────────
    public Map<String, Object> getStorageStateDump() {
        Map<String, Object> state = new LinkedHashMap<>();

        // 1. Memtable state
        Map<String, Object> memState = new LinkedHashMap<>();
        memState.put("fillPercent", memtable.fillPercent());
        memState.put("sizeBytes", memtable.sizeBytes());
        memState.put("capacityBytes", memtableMaxBytes);
        memState.put("entryCount", memtable.entryCount());
        memState.put("liveCount", memtable.liveCount());
        
        var snapshot = memtable.snapshot();
        List<Map<String, Object>> memKeys = snapshot.entrySet().stream()
                .limit(50) // limit to avoid huge payload
                .map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("key", e.getKey());
                    entry.put("value", e.getValue().tombstone() ? null : new String(e.getValue().value(), java.nio.charset.StandardCharsets.UTF_8));
                    entry.put("tombstone", e.getValue().tombstone());
                    return entry;
                })
                .collect(Collectors.toList());
        memState.put("entries", memKeys);
        state.put("memtable", memState);

        // 2. SSTables state
        List<Map<String, Object>> sstList = sstables.stream().map(sst -> {
            var meta = sst.metadata();
            Map<String, Object> sstMap = new LinkedHashMap<>();
            sstMap.put("filename", meta.filename());
            sstMap.put("entryCount", meta.entryCount());
            sstMap.put("firstKey", meta.firstKey());
            sstMap.put("lastKey", meta.lastKey());
            sstMap.put("createdAtMs", meta.createdAtMs());
            sstMap.put("sizeBytes", meta.sizeBytes());
            sstMap.put("sizeHuman", meta.sizeHuman());
            return sstMap;
        }).collect(Collectors.toList());
        state.put("sstables", sstList);

        return state;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        log.info("LsmStorageEngine shutting down.");
        if (ttlReaper != null) ttlReaper.stop();
        compactionManager.shutdown();
        // Flush remaining memtable entries
        forceFlush();
        walWriter.close();
        for (SSTableReader r : sstables) {
            try { r.close(); } catch (IOException ignored) {}
        }
        log.info("LsmStorageEngine closed.");
    }

    // ─── Private: startup recovery ────────────────────────────────────────────

    /**
     * Scans the data directory for {@code .sst} files and opens a reader for each,
     * ordered newest → oldest (lexicographic filename order = chronological order
     * since filenames begin with the creation timestamp).
     */
    private void loadSSTables() throws IOException {
        if (!Files.exists(dataDir)) return;

        List<Path> sstFiles;
        try (var stream = Files.list(dataDir)) {
            sstFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".sst"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed()) // newest first
                    .collect(Collectors.toList());
        }

        for (Path sstPath : sstFiles) {
            try {
                long size = Files.size(sstPath);
                // Minimal metadata for startup — firstKey/lastKey are populated by SSTableReader
                SSTableMetadata meta = new SSTableMetadata(
                        sstPath, 0, "", "", Files.getLastModifiedTime(sstPath).toMillis(), size);
                sstables.add(new SSTableReader(meta));
                log.debug("Loaded SSTable: {}", sstPath.getFileName());
            } catch (Exception e) {
                log.warn("Skipping corrupt/unreadable SSTable {}: {}", sstPath.getFileName(), e.getMessage());
            }
        }
        log.info("Loaded {} SSTable(s) from {}", sstables.size(), dataDir);
    }

    private int replayWal() throws IOException {
        List<WalEntry> entries = WalReader.readAll(walPath());
        for (WalEntry entry : entries) {
            switch (entry.operation()) {
                case PUT    -> memtable.put(entry.key(), buildEntry(entry.value(), false));
                case DELETE -> memtable.put(entry.key(), buildEntry(null, true));
            }
        }
        return entries.size();
    }

    // ─── Private: WAL rotation ────────────────────────────────────────────────

    /**
     * After a successful flush, deletes the old WAL (its data is now in the SSTable)
     * and opens a fresh one. Called while holding the write lock.
     */
    private void rotateWal() {
        try {
            walWriter.close();
            Path oldWal = walPath();
            if (Files.exists(oldWal)) {
                Files.delete(oldWal);
                log.debug("Old WAL deleted after flush.");
            }
            walWriter = new WalWriter(oldWal);
        } catch (Exception e) {
            log.warn("WAL rotation failed (non-fatal): {}", e.getMessage());
        }
    }

    private Path walPath() {
        return dataDir.resolve("wal.log");
    }

    private static final java.util.concurrent.atomic.AtomicLong VERSION =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    private static ValueEntry buildEntry(byte[] value, boolean tombstone) {
        return buildEntry(value, tombstone, 0L);
    }

    private static ValueEntry buildEntry(byte[] value, boolean tombstone, long ttlMs) {
        long version = VERSION.incrementAndGet();
        if (tombstone) return ValueEntry.tombstone(version);
        return ValueEntry.of(value, version, ttlMs);
    }

    /**
     * Scans the memtable for entries that have passed their TTL and writes a tombstone
     * for each expired key. Called by {@link com.kvstore.engine.ttl.TtlReaper} every 10s.
     *
     * @return the number of expired keys evicted
     */
    public int evictExpiredFromMemtable() {
        // Take a snapshot of current keys without holding the write lock
        var snapshot = memtable.snapshot();
        int evicted = 0;
        for (var entry : snapshot.entrySet()) {
            if (entry.getValue().isExpired()) {
                // delete() writes a tombstone — LRU will also be invalidated
                delete(entry.getKey());
                evicted++;
            }
        }
        return evicted;
    }
}
