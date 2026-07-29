package com.kvstore.engine.sstable;

import com.kvstore.engine.StorageException;
import com.kvstore.engine.ValueEntry;
import com.kvstore.engine.bloomfilter.BloomFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Day 5: Reads a single SSTable file and serves point-lookup queries.
 *
 * <h2>Read algorithm</h2>
 * <ol>
 *   <li><b>Open:</b> Read the footer (last 20 bytes) to get the index block offset
 *       and total entry count.</li>
 *   <li><b>Load index:</b> Read the sparse index block entirely into memory.
 *       Typically {@code entryCount / INDEX_INTERVAL} entries — a few hundred KB at most.</li>
 *   <li><b>GET(key):</b> Binary-search the index to find the largest index key
 *       that is {@code ≤ target}. Seek to that data-block offset. Scan forward
 *       linearly until the key is found or the next key exceeds the target.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe: each caller should hold its own {@code SSTableReader} or
 * synchronize externally. {@link com.kvstore.engine.LsmStorageEngine} serialises
 * reads through its own concurrency model.
 *
 * <h2>Resource lifecycle</h2>
 * <p>Implements {@link Closeable}. The underlying {@link RandomAccessFile} is kept
 * open for the lifetime of the reader so we avoid repeated open/close costs on the
 * hot read path.
 */
public class SSTableReader implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SSTableReader.class);

    private final SSTableMetadata metadata;
    private final RandomAccessFile raf;

    // In-memory sparse index — loaded once on construction
    private final List<String> indexKeys    = new ArrayList<>();
    private final List<Long>   indexOffsets = new ArrayList<>();

    /** Bloom filter loaded from the BLOOM section. May be null for old-format files. */
    private BloomFilter bloomFilter;

    /** Byte offset of the bloom block within the file. */
    private long bloomBlockOffset = -1;

    /**
     * Metadata that may be updated after index load to reflect real firstKey/lastKey.
     * Use this field (not the constructor parameter) for range checks.
     */
    private SSTableMetadata effectiveMetadata;

    /**
     * Opens the SSTable file and loads the sparse index into memory.
     *
     * @param metadata Metadata describing the SSTable file.
     * @throws StorageException if the file cannot be read or has an invalid footer.
     */
    public SSTableReader(SSTableMetadata metadata) {
        this.metadata = metadata;
        this.effectiveMetadata = metadata;
        try {
            this.raf = new RandomAccessFile(metadata.path().toFile(), "r");
            loadIndex();
            // effectiveMetadata is updated inside loadIndex() with real firstKey/lastKey
        } catch (IOException e) {
            throw new StorageException("Cannot open SSTable: " + metadata.path(), e);
        }
        log.debug("SSTableReader opened: {} ({} index entries, range [{} … {}])",
                metadata.filename(), indexKeys.size(),
                effectiveMetadata.firstKey(), effectiveMetadata.lastKey());
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Looks up a key in this SSTable.
     *
     * @param key the key to search for.
     * @return the most-recent {@link ValueEntry} for {@code key} (may be a tombstone),
     *         or empty if the key is not in this SSTable.
     */
    public Optional<ValueEntry> get(String key) {
        // 0. Bloom filter fast-path: skip disk entirely if key definitely absent
        if (bloomFilter != null && !bloomFilter.mightContain(key)) {
            return Optional.empty();
        }
        // 1. Fast range pre-check using effectiveMetadata (has real firstKey/lastKey)
        if (!effectiveMetadata.mightContain(key)) {
            return Optional.empty();
        }

        // 2. Binary search the sparse index to find the nearest preceding key
        long dataOffset = findDataOffset(key);
        if (dataOffset < 0) {
            return Optional.empty();
        }

        // 3. Scan forward from that data offset
        return scanForKey(key, dataOffset);
    }

    /**
     * Returns an iterator over all entries in this SSTable, in sorted (ascending) key order.
     * Used by the compaction k-way merge.
     */
    public Iterator<Map.Entry<String, ValueEntry>> iterator() {
        return new SstIterator();
    }

    public SSTableMetadata metadata() {
        return effectiveMetadata;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    // ─── Private: index loading + metadata derivation ─────────────────────────

    /**
     * Reads the footer, loads the sparse index, and loads the Bloom filter.
     *
     * <p>Supports two footer formats:
     * <ul>
     *   <li><b>New (28 bytes):</b> [8B indexOffset][8B bloomOffset][4B entryCount][8B magic]</li>
     *   <li><b>Legacy (20 bytes):</b> [8B indexOffset][4B entryCount][8B magic] — no bloom filter</li>
     * </ul>
     */
    private void loadIndex() throws IOException {
        long fileLen = raf.length();

        // ── Try new 28-byte footer first ──────────────────────────────────────
        if (fileLen >= SSTableWriter.FOOTER_SIZE) {
            raf.seek(fileLen - SSTableWriter.FOOTER_SIZE);
            long indexBlockOffset = raf.readLong();
            long bloomOffset      = raf.readLong();
            int  totalEntryCount  = raf.readInt();
            long magic            = raf.readLong();

            if (magic == SSTableWriter.MAGIC) {
                log.debug("SSTable footer (v2 28B): indexOffset={}, bloomOffset={}, entries={}",
                        indexBlockOffset, bloomOffset, totalEntryCount);
                loadIndexBlock(indexBlockOffset, totalEntryCount);
                loadBloomBlock(bloomOffset, totalEntryCount);
                return;
            }
        }

        // ── Fallback: legacy 20-byte footer (files written before bloom filter) ─
        if (fileLen >= 20) {
            raf.seek(fileLen - 20);
            long indexBlockOffset = raf.readLong();
            int  totalEntryCount  = raf.readInt();
            long magic            = raf.readLong();

            if (magic == SSTableWriter.MAGIC) {
                log.debug("SSTable footer (v1 legacy 20B): indexOffset={}, entries={}",
                        indexBlockOffset, totalEntryCount);
                loadIndexBlock(indexBlockOffset, totalEntryCount);
                return; // no bloom filter in legacy files
            }
        }

        throw new StorageException("SSTable magic mismatch or corrupt footer: " + metadata.path());
    }

    private void loadIndexBlock(long indexBlockOffset, int totalEntryCount) throws IOException {
        raf.seek(indexBlockOffset);
        int indexEntryCount = raf.readInt();
        for (int i = 0; i < indexEntryCount; i++) {
            int    keyLen = raf.readInt();
            byte[] keyB   = new byte[keyLen];
            raf.readFully(keyB);
            long offset = raf.readLong();
            indexKeys.add(new String(keyB, StandardCharsets.UTF_8));
            indexOffsets.add(offset);
        }

        if (!indexKeys.isEmpty()) {
            String firstKey = indexKeys.get(0);
            String lastKey  = findLastDataKey(indexOffsets.get(indexOffsets.size() - 1), indexBlockOffset);
            this.effectiveMetadata = new SSTableMetadata(
                    metadata.path(), totalEntryCount, firstKey, lastKey,
                    metadata.createdAtMs(), metadata.sizeBytes());
        }
    }

    private void loadBloomBlock(long bloomOffset, int totalEntryCount) {
        this.bloomBlockOffset = bloomOffset;
        try {
            raf.seek(bloomOffset);
            int bloomLen = raf.readInt();
            if (bloomLen > 0 && bloomLen < 10_000_000) {
                byte[] bloomBytes = new byte[bloomLen];
                raf.readFully(bloomBytes);
                this.bloomFilter = new BloomFilter(bloomBytes, totalEntryCount);
                log.debug("Bloom filter loaded: {} bytes, expectedKeys={}", bloomLen, totalEntryCount);
            }
        } catch (IOException e) {
            log.warn("Could not load bloom filter from {}: {}", metadata.path(), e.getMessage());
        }
    }

    /**
     * Scans forward from {@code startDataOffset} through data entries (stopping at
     * {@code indexBlockOffset}) to find the last key written in the data block.
     */
    private String findLastDataKey(long startDataOffset, long indexBlockOffset) {
        String lastKey = indexKeys.get(indexKeys.size() - 1); // safe fallback
        try {
            raf.seek(startDataOffset);
            while (raf.getFilePointer() < indexBlockOffset) {
                int keyLen;
                try { keyLen = raf.readInt(); } catch (EOFException e) { break; }
                if (keyLen <= 0 || keyLen > 4096) break;
                byte[] kb = new byte[keyLen];
                raf.readFully(kb);
                lastKey = new String(kb, StandardCharsets.UTF_8);
                byte flags   = raf.readByte();
                int valueLen = raf.readInt();
                if (valueLen > 0) raf.skipBytes(valueLen);
                raf.skipBytes(8); // version long
            }
        } catch (IOException e) {
            log.warn("Could not derive lastKey accurately: {}", e.getMessage());
        }
        return lastKey;
    }

    // ─── Private: lookup ──────────────────────────────────────────────────────

    /**
     * Binary-searches the sparse index to find the data-block offset of the
     * largest index key that is {@code ≤ target}.
     *
     * @return data-block offset, or {@code -1} if target is before all index keys.
     */
    private long findDataOffset(String target) {
        if (indexKeys.isEmpty()) return 0L;

        int lo = 0, hi = indexKeys.size() - 1, result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = indexKeys.get(mid).compareTo(target);
            if (cmp <= 0) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return (result < 0) ? 0L : indexOffsets.get(result);
    }

    /**
     * Linearly scans the data block from {@code startOffset}, comparing each key
     * against {@code target}. Stops as soon as the current key exceeds the target
     * (keys are sorted, so no match is possible beyond this point).
     */
    private Optional<ValueEntry> scanForKey(String target, long startOffset) {
        // stop scanning when we reach the index block (or bloom block if present)
        long scanBound = bloomBlockOffset > 0 ? bloomBlockOffset
                : effectiveMetadata.sizeBytes() - SSTableWriter.FOOTER_SIZE;
        try {
            raf.seek(startOffset);

            while (raf.getFilePointer() < scanBound) {
                // Peek: try to read the next key
                int keyLen;
                try {
                    keyLen = raf.readInt();
                } catch (EOFException e) {
                    break;
                }

                // Safety guard against corrupt index pointing into non-data area
                if (keyLen <= 0 || keyLen > 4096) break;

                byte[] keyBytes = new byte[keyLen];
                raf.readFully(keyBytes);
                String currentKey = new String(keyBytes, StandardCharsets.UTF_8);

                int cmp = currentKey.compareTo(target);

                byte flags    = raf.readByte();
                int  valueLen = raf.readInt();
                byte[] value  = null;
                if (valueLen > 0) {
                    value = new byte[valueLen];
                    raf.readFully(value);
                }
                long version = raf.readLong();

                if (cmp == 0) {
                    // Found — build the ValueEntry (expiryMs=0: SSTables don't persist TTL)
                    boolean tombstone = (flags & SSTableWriter.FLAG_TOMBSTONE) != 0;
                    return Optional.of(new ValueEntry(tombstone ? null : value,
                            version,
                            System.currentTimeMillis(),
                            tombstone,
                            0L));
                }

                if (cmp > 0) {
                    // Current key has passed target — no match in sorted order
                    break;
                }
                // cmp < 0 → keep scanning forward
            }
        } catch (IOException e) {
            throw new StorageException("Error reading SSTable " + metadata.path(), e);
        }
        return Optional.empty();
    }

    // ─── Factory ──────────────────────────────────────────────────────────────

    /**
     * Convenience factory: opens an existing SSTable from its file path.
     * Derives metadata from the file.
     */
    public static SSTableReader open(Path path) throws IOException {
        long size = Files.size(path);
        SSTableMetadata placeholder = new SSTableMetadata(path, 0, "", "", 0L, size);
        return new SSTableReader(placeholder);
    }

    // ─── SstIterator (used by CompactionManager) ─────────────────────────────

    /**
     * Iterates over ALL data entries in this SSTable in key-sorted order.
     * Used by {@link com.kvstore.engine.compaction.CompactionManager} for k-way merge.
     * Opens a fresh {@link RandomAccessFile} so it does not interfere with concurrent GETs.
     */
    private class SstIterator implements Iterator<Map.Entry<String, ValueEntry>> {

        private final RandomAccessFile iterRaf;
        private final long             bound; // exclusive upper bound (index/bloom block start)
        private Map.Entry<String, ValueEntry> next;

        SstIterator() {
            try {
                this.iterRaf = new RandomAccessFile(effectiveMetadata.path().toFile(), "r");
                this.bound   = bloomBlockOffset > 0 ? bloomBlockOffset
                        : (effectiveMetadata.sizeBytes() - SSTableWriter.FOOTER_SIZE);
                this.iterRaf.seek(0);
                this.next = readNext();
            } catch (IOException e) {
                throw new StorageException("Cannot open SSTable iterator: " + effectiveMetadata.path(), e);
            }
        }

        @Override public boolean hasNext() { return next != null; }

        @Override
        public Map.Entry<String, ValueEntry> next() {
            if (next == null) throw new NoSuchElementException();
            Map.Entry<String, ValueEntry> current = next;
            next = readNext();
            return current;
        }

        private Map.Entry<String, ValueEntry> readNext() {
            try {
                while (iterRaf.getFilePointer() < bound) {
                    int keyLen;
                    try { keyLen = iterRaf.readInt(); } catch (EOFException e) { break; }
                    if (keyLen <= 0 || keyLen > 4096) break;
                    byte[] kb = new byte[keyLen];
                    iterRaf.readFully(kb);
                    String key    = new String(kb, StandardCharsets.UTF_8);
                    byte   flags  = iterRaf.readByte();
                    int    valLen = iterRaf.readInt();
                    byte[] val    = null;
                    if (valLen > 0) { val = new byte[valLen]; iterRaf.readFully(val); }
                    long   ver    = iterRaf.readLong();
                    boolean tomb  = (flags & SSTableWriter.FLAG_TOMBSTONE) != 0;
                    // expiryMs=0: SSTables don't store TTL — expired entries cleaned by compaction
                    ValueEntry entry = new ValueEntry(tomb ? null : val, ver, System.currentTimeMillis(), tomb, 0L);
                    return Map.entry(key, entry);
                }
                iterRaf.close();
            } catch (IOException e) {
                try { iterRaf.close(); } catch (IOException ignored) {}
                throw new StorageException("SSTable iteration error", e);
            }
            return null;
        }
    }
}
