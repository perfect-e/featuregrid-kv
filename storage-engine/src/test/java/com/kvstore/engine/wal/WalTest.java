package com.kvstore.engine.wal;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link WalWriter} and {@link WalReader}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic write → read round-trip for PUT and DELETE</li>
 *   <li>Multiple entries in order</li>
 *   <li>Large values (1 MB)</li>
 *   <li>CRC32 mismatch detection — reading stops at corrupt record</li>
 *   <li>Truncated tail record — reading stops gracefully</li>
 *   <li>Empty / non-existent WAL file</li>
 *   <li>1 000-entry bulk round-trip</li>
 * </ul>
 */
@DisplayName("WalWriter + WalReader")
class WalTest {

    @TempDir
    Path tempDir;

    private Path walPath;

    @BeforeEach
    void setUp() {
        walPath = tempDir.resolve("wal.log");
    }

    // ─── Basic round-trips ────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT entry survives write → read round-trip")
    void putRoundTrip() throws Exception {
        byte[] value = "hello-world".getBytes();

        try (WalWriter writer = new WalWriter(walPath)) {
            writer.append(WalEntry.put("user:1", value));
        }

        List<WalEntry> entries = WalReader.readAll(walPath);

        assertThat(entries).hasSize(1);
        WalEntry e = entries.get(0);
        assertThat(e.operation()).isEqualTo(WalOperation.PUT);
        assertThat(e.key()).isEqualTo("user:1");
        assertThat(e.value()).isEqualTo(value);
    }

    @Test
    @DisplayName("DELETE entry survives write → read round-trip")
    void deleteRoundTrip() throws Exception {
        try (WalWriter writer = new WalWriter(walPath)) {
            writer.append(WalEntry.delete("user:42"));
        }

        List<WalEntry> entries = WalReader.readAll(walPath);

        assertThat(entries).hasSize(1);
        WalEntry e = entries.get(0);
        assertThat(e.operation()).isEqualTo(WalOperation.DELETE);
        assertThat(e.key()).isEqualTo("user:42");
        assertThat(e.value()).isNull();
    }

    @Test
    @DisplayName("Mixed PUT + DELETE entries are read back in order")
    void mixedEntriesPreserveOrder() throws Exception {
        try (WalWriter writer = new WalWriter(walPath)) {
            writer.append(WalEntry.put("k1", "v1".getBytes()));
            writer.append(WalEntry.put("k2", "v2".getBytes()));
            writer.append(WalEntry.delete("k1"));
            writer.append(WalEntry.put("k3", "v3".getBytes()));
        }

        List<WalEntry> entries = WalReader.readAll(walPath);

        assertThat(entries).hasSize(4);
        assertThat(entries.get(0)).isEqualTo(WalEntry.put("k1", "v1".getBytes()));
        assertThat(entries.get(1)).isEqualTo(WalEntry.put("k2", "v2".getBytes()));
        assertThat(entries.get(2)).isEqualTo(WalEntry.delete("k1"));
        assertThat(entries.get(3)).isEqualTo(WalEntry.put("k3", "v3".getBytes()));
    }

    // ─── Edge values ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Single-byte value round-trips correctly")
    void singleByteValue() throws Exception {
        byte[] value = {(byte) 0xFF};
        try (WalWriter writer = new WalWriter(walPath)) {
            writer.append(WalEntry.put("byte-key", value));
        }
        List<WalEntry> entries = WalReader.readAll(walPath);
        assertThat(entries.get(0).value()).isEqualTo(value);
    }

    @Test
    @DisplayName("Large value (1 MB) round-trips correctly")
    void largeValue() throws Exception {
        byte[] bigValue = new byte[1024 * 1024];
        new Random(42).nextBytes(bigValue);

        try (WalWriter writer = new WalWriter(walPath)) {
            writer.append(WalEntry.put("big-key", bigValue));
        }

        List<WalEntry> entries = WalReader.readAll(walPath);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).value()).isEqualTo(bigValue);
    }

    @Test
    @DisplayName("Key with special characters round-trips correctly")
    void specialCharKey() throws Exception {
        String key = "ns:user:1/profile?v=2&lang=en";
        try (WalWriter writer = new WalWriter(walPath)) {
            writer.append(WalEntry.put(key, "data".getBytes()));
        }
        List<WalEntry> entries = WalReader.readAll(walPath);
        assertThat(entries.get(0).key()).isEqualTo(key);
    }

    // ─── Corruption & truncation handling ────────────────────────────────────

    @Test
    @DisplayName("Reading non-existent WAL returns empty list")
    void nonExistentWal() throws Exception {
        Path missing = tempDir.resolve("missing.log");
        List<WalEntry> entries = WalReader.readAll(missing);
        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Empty WAL file returns empty list")
    void emptyWal() throws Exception {
        Files.createFile(walPath);
        List<WalEntry> entries = WalReader.readAll(walPath);
        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("CRC mismatch: good records before corrupt one are returned; corrupt and subsequent are dropped")
    void crcMismatchStopsReplay() throws Exception {
        // Write 2 valid records
        try (WalWriter writer = new WalWriter(walPath)) {
            writer.append(WalEntry.put("k1", "v1".getBytes()));
            writer.append(WalEntry.put("k2", "v2".getBytes()));
        }

        // Corrupt some bytes in the middle of the file (inside the second record's payload)
        byte[] raw = Files.readAllBytes(walPath);
        // first record: 4 (len) + payload + 4 (crc) — flip a byte in the second record
        int firstRecordTotalSize = 4 + readInt(raw, 0) + 4;
        if (firstRecordTotalSize < raw.length - 1) {
            raw[firstRecordTotalSize + 5] ^= 0xFF;  // flip a byte in second record's payload
        }
        Files.write(walPath, raw);

        List<WalEntry> recovered = WalReader.readAll(walPath);

        // Only the first (uncorrupted) record should be returned
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).key()).isEqualTo("k1");
    }

    @Test
    @DisplayName("Truncated tail record: complete records before truncation are returned")
    void truncatedTailRecord() throws Exception {
        // Write 3 valid records
        try (WalWriter writer = new WalWriter(walPath)) {
            writer.append(WalEntry.put("k1", "v1".getBytes()));
            writer.append(WalEntry.put("k2", "v2".getBytes()));
            writer.append(WalEntry.put("k3", "v3".getBytes()));
        }

        // Truncate the file by removing the last 10 bytes (simulates kill -9 mid-write)
        byte[] raw = Files.readAllBytes(walPath);
        byte[] truncated = new byte[raw.length - 10];
        System.arraycopy(raw, 0, truncated, 0, truncated.length);
        Files.write(walPath, truncated);

        List<WalEntry> recovered = WalReader.readAll(walPath);

        // At least the first 2 complete records must be recovered
        assertThat(recovered.size()).isGreaterThanOrEqualTo(2);
        assertThat(recovered.get(0).key()).isEqualTo("k1");
        assertThat(recovered.get(1).key()).isEqualTo("k2");
    }

    // ─── Bulk ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1 000 entries round-trip: all keys and values match")
    void bulkRoundTrip() throws Exception {
        int N = 1_000;
        try (WalWriter writer = new WalWriter(walPath)) {
            for (int i = 0; i < N; i++) {
                writer.append(WalEntry.put("key:" + i, ("value:" + i).getBytes()));
            }
        }

        List<WalEntry> entries = WalReader.readAll(walPath);
        assertThat(entries).hasSize(N);

        for (int i = 0; i < N; i++) {
            assertThat(entries.get(i).key()).isEqualTo("key:" + i);
            assertThat(entries.get(i).value()).isEqualTo(("value:" + i).getBytes());
        }
    }

    @Test
    @DisplayName("Append across multiple WalWriter sessions (simulates normal shutdown + restart)")
    void appendAcrossSessions() throws Exception {
        // Session 1
        try (WalWriter w = new WalWriter(walPath)) {
            w.append(WalEntry.put("a", "1".getBytes()));
        }
        // Session 2 (append mode must not truncate)
        try (WalWriter w = new WalWriter(walPath)) {
            w.append(WalEntry.put("b", "2".getBytes()));
        }

        List<WalEntry> entries = WalReader.readAll(walPath);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).key()).isEqualTo("a");
        assertThat(entries.get(1).key()).isEqualTo("b");
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /** Reads a 4-byte big-endian int from a byte array at the given offset. */
    private static int readInt(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
             | ((buf[offset + 1] & 0xFF) << 16)
             | ((buf[offset + 2] & 0xFF) << 8)
             |  (buf[offset + 3] & 0xFF);
    }
}
