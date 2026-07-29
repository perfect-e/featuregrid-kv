package com.kvstore.engine.sstable;

import java.nio.file.Path;

/**
 * Immutable metadata for a single SSTable file.
 *
 * <p>Loaded on startup (from the data directory) and kept in memory so that
 * {@link com.kvstore.engine.LsmStorageEngine} can make decisions like:
 * <ul>
 *   <li>Search order: newest SSTable first (highest {@code createdAtMs}).</li>
 *   <li>Compaction candidate: pick SSTables with the most entries.</li>
 *   <li>Range skip: skip this SSTable if {@code key < firstKey || key > lastKey}.</li>
 * </ul>
 *
 * @param path          Absolute path to the {@code .sst} file on disk.
 * @param entryCount    Total number of entries (including tombstones) written during the flush.
 * @param firstKey      Lexicographically smallest key in this SSTable.
 * @param lastKey       Lexicographically largest key in this SSTable.
 * @param createdAtMs   {@link System#currentTimeMillis()} at the time the file was written.
 * @param sizeBytes     File size in bytes (data + index + footer).
 */
public record SSTableMetadata(
        Path path,
        int entryCount,
        String firstKey,
        String lastKey,
        long createdAtMs,
        long sizeBytes
) {
    /** Returns the filename portion of the path (e.g. {@code sst-1722000000-42.sst}). */
    public String filename() {
        return path.getFileName().toString();
    }

    /** Human-readable size string (KB / MB). */
    public String sizeHuman() {
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024 * 1024) return String.format("%.1f KB", sizeBytes / 1024.0);
        return String.format("%.2f MB", sizeBytes / (1024.0 * 1024));
    }

    /**
     * Quick key-range check: {@code true} if {@code key} could possibly be in
     * this SSTable based on its first/last key bounds.
     *
     * <p>This is a cheap O(1) pre-filter before the O(log n) binary search.
     */
    public boolean mightContain(String key) {
        return key.compareTo(firstKey) >= 0 && key.compareTo(lastKey) <= 0;
    }
}
