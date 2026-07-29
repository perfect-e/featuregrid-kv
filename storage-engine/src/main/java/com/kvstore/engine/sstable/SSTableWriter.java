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
import java.util.Map;
import java.util.NavigableMap;

/**
 * Day 5: Flushes a sorted memtable snapshot to an immutable SSTable file on disk.
 *
 * <h2>SSTable File Layout</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  DATA BLOCK  (from byte 0)                                       │
 * │  Sorted entries — one per key (including tombstones):            │
 * │    [4B keyLen][key bytes][1B flags][4B valueLen or -1][value]    │
 * │    [8B version]                                                   │
 * ├──────────────────────────────────────────────────────────────────┤
 * │  INDEX BLOCK  (sparse, one entry every INDEX_INTERVAL entries)   │
 * │    [4B indexEntryCount]                                           │
 * │    per entry: [4B keyLen][key bytes][8B dataBlockByteOffset]     │
 * ├──────────────────────────────────────────────────────────────────┤
 * │  BLOOM FILTER BLOCK                                               │
 * │    [4B bloomByteLen][bloom bit-array bytes]                       │
 * ├──────────────────────────────────────────────────────────────────┤
 * │  FOOTER  (last 28 bytes of file)                                  │
 * │    [8B indexBlockOffset][8B bloomBlockOffset]                     │
 * │    [4B totalEntryCount][8B magic]                                 │
 * └──────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Flags byte</h2>
 * <ul>
 *   <li>bit 0 ({@code 0x01}) = tombstone (DELETE). Value bytes are absent.</li>
 *   <li>Other bits reserved for future use (compression, encryption hints).</li>
 * </ul>
 *
 * <h2>Index sparsity</h2>
 * <p>One index entry is written every {@value #INDEX_INTERVAL} data entries.
 * At read time, we binary-search the index to find the nearest preceding key,
 * then scan forward in the data block to find the exact match. This bounds the
 * linear scan to at most {@value #INDEX_INTERVAL} comparisons per lookup.
 *
 * <h2>Filename convention</h2>
 * <p>Files are named {@code sst-<createdAtMs>-<random>.sst} so that lexicographic
 * filename sort equals chronological order.
 */
public class SSTableWriter {

    private static final Logger log = LoggerFactory.getLogger(SSTableWriter.class);

    /** One index entry is emitted every N data entries. */
    public static final int INDEX_INTERVAL = 16;

    /** Magic bytes in the footer: "KVSTBL1\0" encoded as a long. */
    static final long MAGIC = 0x4B5653_54424C_3100L;

    /** Footer is always 28 bytes: [8B indexOffset][8B bloomOffset][4B entryCount][8B magic]. */
    static final int FOOTER_SIZE = 28;

    // ─── Flags ───────────────────────────────────────────────────────────────
    static final byte FLAG_TOMBSTONE = 0x01;

    private SSTableWriter() { /* static utility class */ }

    /**
     * Flushes a sorted memtable snapshot to a new SSTable file.
     *
     * @param dir      Directory where the SSTable file will be created.
     * @param snapshot Sorted, ascending view of the memtable (must not be empty).
     * @return Metadata describing the newly created SSTable.
     * @throws StorageException if the file cannot be written.
     */
    public static SSTableMetadata write(Path dir, NavigableMap<String, ValueEntry> snapshot) {
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("Cannot flush an empty memtable.");
        }

        String filename = "sst-%d-%04d.sst".formatted(
                System.currentTimeMillis(),
                (int) (Math.random() * 9999));
        Path sstPath = dir.resolve(filename);

        log.info("Flushing {} entries to SSTable: {}", snapshot.size(), filename);
        long startMs = System.currentTimeMillis();

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new StorageException("Cannot create SSTable directory: " + dir, e);
        }

        // ── Index: collect (key, offset) pairs as we write the data block ────
        // We only keep an entry every INDEX_INTERVAL entries for sparsity.
        java.util.List<long[]>  indexOffsets = new java.util.ArrayList<>();
        java.util.List<String>  indexKeys    = new java.util.ArrayList<>();

        String firstKey = snapshot.firstKey();
        String lastKey  = snapshot.lastKey();
        int entryCount  = 0;
        long dataBlockLen;

        try (FileOutputStream fos = new FileOutputStream(sstPath.toFile());
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fos, 64 * 1024))) {

            long currentOffset = 0;

            // ── DATA BLOCK ───────────────────────────────────────────────────
            for (Map.Entry<String, ValueEntry> e : snapshot.entrySet()) {
                // Record index entry every INDEX_INTERVAL entries
                if (entryCount % INDEX_INTERVAL == 0) {
                    indexOffsets.add(new long[]{currentOffset});
                    indexKeys.add(e.getKey());
                }

                long written = writeDataEntry(dos, e.getKey(), e.getValue());
                currentOffset += written;
                entryCount++;
            }

            dataBlockLen = currentOffset;

            // ── BLOOM FILTER BLOCK ───────────────────────────────────────────
            long bloomBlockOffset = currentOffset + (/* index block size computed below */ 0);
            // We need to write index first to know bloomBlockOffset, so we track it
            // by noting our position before index
            long indexStartOffset = currentOffset;

            // Write index block first (we already know its content)
            int indexByteCount = 4; // writeInt(indexKeys.size())
            for (int i = 0; i < indexKeys.size(); i++) {
                indexByteCount += 4 + indexKeys.get(i).getBytes(StandardCharsets.UTF_8).length + 8;
            }
            bloomBlockOffset = indexStartOffset + indexByteCount;

            // ── INDEX BLOCK ──────────────────────────────────────────────────
            long indexBlockOffset = currentOffset;
            dos.writeInt(indexKeys.size());
            for (int i = 0; i < indexKeys.size(); i++) {
                byte[] keyBytes = indexKeys.get(i).getBytes(StandardCharsets.UTF_8);
                dos.writeInt(keyBytes.length);
                dos.write(keyBytes);
                dos.writeLong(indexOffsets.get(i)[0]);
            }

            // ── BLOOM FILTER BLOCK ───────────────────────────────────────────
            BloomFilter bloom = new BloomFilter(entryCount);
            for (String k : snapshot.keySet()) bloom.add(k);
            byte[] bloomBytes = bloom.toBytes();
            dos.writeInt(bloomBytes.length);
            dos.write(bloomBytes);

            // ── FOOTER (always last 28 bytes) ─────────────────────────────────
            dos.writeLong(indexBlockOffset);
            dos.writeLong(bloomBlockOffset);
            dos.writeInt(entryCount);
            dos.writeLong(MAGIC);

            dos.flush();
            fos.getFD().sync();   // durable before we return metadata

        } catch (IOException ex) {
            throw new StorageException("SSTable write failed: " + sstPath, ex);
        }

        long sizeBytes;
        try { sizeBytes = Files.size(sstPath); } catch (IOException e) { sizeBytes = -1; }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("SSTable written: {} ({}) in {}ms — {} entries, keys [{} … {}]",
                filename, humanSize(sizeBytes), elapsed, entryCount, firstKey, lastKey);

        return new SSTableMetadata(sstPath, entryCount, firstKey, lastKey,
                System.currentTimeMillis(), sizeBytes);
    }

    /**
     * Package-private overload used by the compaction engine to write
     * entries from an already-merged iterator rather than a memtable snapshot.
     */
    static SSTableMetadata writeEntries(Path dir, java.util.Iterator<Map.Entry<String, ValueEntry>> entries,
                                        int estimatedCount, String filenameSuffix) {
        if (estimatedCount <= 0) estimatedCount = 1000;
        String filename = "sst-%d-%s.sst".formatted(System.currentTimeMillis(), filenameSuffix);
        Path sstPath = dir.resolve(filename);
        log.info("Compaction writing merged SSTable: {}", filename);

        // Collect into a TreeMap so SSTableWriter can use the existing write() logic
        java.util.TreeMap<String, ValueEntry> map = new java.util.TreeMap<>();
        entries.forEachRemaining(e -> map.put(e.getKey(), e.getValue()));
        if (map.isEmpty()) throw new IllegalArgumentException("No entries to write");
        return write(dir, map);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Serialises one data entry and returns the number of bytes written.
     *
     * <pre>
     *   [4B keyLen][key][1B flags][4B valueLen/-1][value][8B version]
     * </pre>
     */
    static long writeDataEntry(DataOutputStream dos, String key, ValueEntry entry)
            throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(keyBytes.length);
        dos.write(keyBytes);

        if (entry.tombstone()) {
            dos.writeByte(FLAG_TOMBSTONE);
            dos.writeInt(-1);   // no value
        } else {
            dos.writeByte(0);
            dos.writeInt(entry.value().length);
            dos.write(entry.value());
        }
        dos.writeLong(entry.version());

        return 4L + keyBytes.length + 1 + 4 + (entry.tombstone() ? 0 : entry.value().length) + 8;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return "%.1f KB".formatted(bytes / 1024.0);
        return "%.2f MB".formatted(bytes / (1024.0 * 1024));
    }
}
