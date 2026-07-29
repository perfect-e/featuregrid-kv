package com.kvstore.engine.compaction;

import com.kvstore.engine.StorageException;
import com.kvstore.engine.ValueEntry;
import com.kvstore.engine.sstable.SSTableMetadata;
import com.kvstore.engine.sstable.SSTableReader;
import com.kvstore.engine.sstable.SSTableWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Consumer;

/**
 * Day 8: Background size-tiered compaction for the LSM storage engine.
 *
 * <h2>Why compaction is necessary</h2>
 * <pre>
 *   Without compaction:
 *   - A GET for "user:1" must check SST-0, SST-1, SST-2, ... (read amplification)
 *   - Old versions of the same key occupy disk space in every SSTable (space waste)
 *   - Tombstones (DELETE markers) accumulate forever, never reclaiming space
 *
 *   After compaction:
 *   - Multiple SSTables → 1 merged SSTable with only the latest version of each key
 *   - Tombstones are dropped (key truly freed from disk)
 *   - Read path only checks 1-2 SSTables instead of N
 * </pre>
 *
 * <h2>Algorithm: K-Way Merge using a Min-Heap</h2>
 * <pre>
 *   SST-0: [a=1, c=3, e=5]           ← newest (highest version wins)
 *   SST-1: [b=2, c=OLD, d=4]         ← older
 *   SST-2: [a=OLD, b=OLD, f=6]       ← oldest
 *
 *   Step 1: Open an iterator for each SSTable
 *   Step 2: Insert (currentKey, value, sstIndex) into a min-heap keyed on (key, -version)
 *           "Smallest key goes first; among duplicates, latest version wins"
 *   Step 3: Poll from heap. Write entry if key changed. Skip duplicates.
 *   Step 4: Write merged stream to a NEW SSTable file.
 *   Step 5: Atomically swap old SSTable list entry with the new merged file.
 *   Step 6: Delete old SSTable files from disk.
 *
 *   Result: [a=1, b=2, c=3, d=4, e=5, f=6]  ← 1 SSTable, clean, optimal
 * </pre>
 *
 * <h2>Size-tiered trigger</h2>
 * <p>Compaction is triggered when the SSTable count exceeds {@value #COMPACTION_THRESHOLD}.
 * We compact the {@value #FILES_TO_COMPACT} oldest files.
 */
public class CompactionManager {

    private static final Logger log = LoggerFactory.getLogger(CompactionManager.class);

    /** Trigger compaction when SSTable count exceeds this value. */
    private static final int COMPACTION_THRESHOLD = 4;

    /** Number of files to merge per compaction run. */
    private static final int FILES_TO_COMPACT = 4;

    /** Interval between compaction checks (seconds). */
    private static final long CHECK_INTERVAL_SEC = 30;

    // Provided by LsmStorageEngine
    private final Path dataDir;
    private final ReadWriteLock lock;
    private final List<SSTableReader> sstables;    // same CopyOnWriteArrayList used by engine
    private final Consumer<List<SSTableReader>>  onCompactionComplete;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kv-compaction");
                t.setDaemon(true);
                return t;
            });

    public CompactionManager(Path dataDir,
                             ReadWriteLock lock,
                             List<SSTableReader> sstables,
                             Consumer<List<SSTableReader>> onCompactionComplete) {
        this.dataDir              = dataDir;
        this.lock                 = lock;
        this.sstables             = sstables;
        this.onCompactionComplete = onCompactionComplete;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::runIfNeeded,
                CHECK_INTERVAL_SEC,
                CHECK_INTERVAL_SEC,
                TimeUnit.SECONDS);
        log.info("CompactionManager started (checks every {}s, threshold={} files)",
                CHECK_INTERVAL_SEC, COMPACTION_THRESHOLD);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    /** Triggers compaction immediately (useful for tests and the dashboard button). */
    public void triggerNow() {
        scheduler.submit(this::compact);
    }

    // ─── Compaction logic ─────────────────────────────────────────────────────

    private void runIfNeeded() {
        int count = sstables.size();
        if (count >= COMPACTION_THRESHOLD) {
            log.info("Compaction triggered: {} SSTables >= threshold {}", count, COMPACTION_THRESHOLD);
            compact();
        } else {
            log.debug("Compaction skipped: {} SSTables < threshold {}", count, COMPACTION_THRESHOLD);
        }
    }

    /**
     * Compacts the {@value #FILES_TO_COMPACT} oldest SSTables into a single merged file.
     * Thread-safe: holds the write lock only for the atomic swap at the end.
     */
    public void compact() {
        // 1. Snapshot the list of SSTables to compact (oldest = end of list, newest = index 0)
        List<SSTableReader> candidates;
        lock.readLock().lock();
        try {
            if (sstables.size() < 2) return; // nothing to compact
            int fromIndex = Math.max(0, sstables.size() - FILES_TO_COMPACT);
            candidates = new ArrayList<>(sstables.subList(fromIndex, sstables.size()));
        } finally {
            lock.readLock().unlock();
        }

        log.info("Compacting {} SSTable(s): {}",
                candidates.size(),
                candidates.stream().map(r -> r.metadata().filename()).toList());

        long startMs = System.currentTimeMillis();

        // 2. K-way merge (outside the lock — slow disk I/O)
        SSTableMetadata mergedMeta;
        try {
            mergedMeta = kWayMerge(candidates);
        } catch (Exception e) {
            log.error("Compaction merge failed: {}", e.getMessage(), e);
            return;
        }

        // 3. Atomic swap: replace old readers with new merged reader (hold write lock briefly)
        SSTableReader mergedReader = new SSTableReader(mergedMeta);
        lock.writeLock().lock();
        try {
            // Remove the candidate readers from the live list
            sstables.removeAll(candidates);
            // Insert the merged reader at the position where the candidates were
            sstables.add(mergedReader);
        } finally {
            lock.writeLock().unlock();
        }

        // 4. Delete old SSTable files from disk (outside the lock)
        for (SSTableReader old : candidates) {
            try {
                old.close();
                Files.deleteIfExists(old.metadata().path());
                log.debug("Deleted compacted SSTable: {}", old.metadata().filename());
            } catch (IOException e) {
                log.warn("Could not delete old SSTable {}: {}", old.metadata().filename(), e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Compaction done in {}ms. Merged {} SSTables → 1 ({}). Total now: {}",
                elapsed, candidates.size(), mergedMeta.filename(), sstables.size());

        // Notify LsmStorageEngine (for UI metrics)
        onCompactionComplete.accept(sstables);
    }

    // ─── K-Way Merge ──────────────────────────────────────────────────────────

    /**
     * Merges {@code candidates} using a min-heap over their iterators.
     *
     * <p>The heap is keyed on {@code (key ASC, version DESC)} so that among
     * duplicate keys, the entry with the highest version number (latest write)
     * is emitted first. All subsequent entries with the same key are discarded.
     *
     * <p>Tombstones are only dropped when no older candidate could have a live entry
     * for the same key (i.e., when {@code candidates} covers the complete key history).
     * Since we compact the OLDEST files together, tombstones in this compaction run
     * can safely be dropped.
     */
    private SSTableMetadata kWayMerge(List<SSTableReader> candidates) {
        // ── Heap entry ────────────────────────────────────────────────────────
        record HeapEntry(String key, ValueEntry entry, Iterator<Map.Entry<String, ValueEntry>> source)
                implements Comparable<HeapEntry> {
            @Override
            public int compareTo(HeapEntry o) {
                int cmp = this.key.compareTo(o.key);
                if (cmp != 0) return cmp;
                // Same key: higher version (newer write) should come first → negate
                return Long.compare(o.entry.version(), this.entry.version());
            }
        }

        // ── Initialise heap with the first entry from each iterator ───────────
        PriorityQueue<HeapEntry> heap = new PriorityQueue<>();
        List<Iterator<Map.Entry<String, ValueEntry>>> iters = new ArrayList<>();
        for (SSTableReader reader : candidates) {
            Iterator<Map.Entry<String, ValueEntry>> it = reader.iterator();
            if (it.hasNext()) {
                Map.Entry<String, ValueEntry> e = it.next();
                heap.add(new HeapEntry(e.getKey(), e.getValue(), it));
                iters.add(it);
            }
        }

        // ── Stream the merged result into a TreeMap (for SSTableWriter) ───────
        TreeMap<String, ValueEntry> merged = new TreeMap<>();
        String lastKey = null;

        while (!heap.isEmpty()) {
            HeapEntry top = heap.poll();

            // Skip duplicates (same key, lower version)
            if (top.key().equals(lastKey)) {
                // Advance this iterator but don't add to result
            } else {
                lastKey = top.key();
                // Drop tombstones during compaction (since we're merging the oldest files,
                // no earlier SSTable can have a live version of this key)
                if (!top.entry().tombstone()) {
                    merged.put(top.key(), top.entry());
                }
            }

            // Advance this iterator
            if (top.source().hasNext()) {
                Map.Entry<String, ValueEntry> next = top.source().next();
                heap.add(new HeapEntry(next.getKey(), next.getValue(), top.source()));
            }
        }

        if (merged.isEmpty()) {
            throw new StorageException("Compaction produced an empty result — all entries were tombstones.");
        }

        return SSTableWriter.write(dataDir, merged);
    }
}
