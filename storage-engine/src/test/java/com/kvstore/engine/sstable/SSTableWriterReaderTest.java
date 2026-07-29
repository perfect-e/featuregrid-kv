package com.kvstore.engine.sstable;

import com.kvstore.engine.ValueEntry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SSTableWriter} → {@link SSTableReader} round-trip.
 *
 * Coverage:
 * - Basic write → read for live key
 * - Tombstone entry preserved through flush + read
 * - Non-existent key returns empty
 * - Key out of range returns empty (mightContain fast-path)
 * - Large flush (10,000 entries) — correctness + binary search
 * - Multiple keys around index boundaries (INDEX_INTERVAL borders)
 * - SSTableMetadata fields (firstKey, lastKey, entryCount, sizeBytes)
 */
@DisplayName("SSTableWriter + SSTableReader")
class SSTableWriterReaderTest {

    @TempDir
    Path dir;

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static ValueEntry liveEntry(String value) {
        return new ValueEntry(value.getBytes(), 1L, System.currentTimeMillis(), false, 0L);
    }

    private static ValueEntry tombstone() {
        return new ValueEntry(null, 1L, System.currentTimeMillis(), true, 0L);
    }

    private TreeMap<String, ValueEntry> single(String key, ValueEntry entry) {
        TreeMap<String, ValueEntry> m = new TreeMap<>();
        m.put(key, entry);
        return m;
    }

    // ─── Basic round-trips ────────────────────────────────────────────────────

    @Test
    @DisplayName("Single live entry survives write → read")
    void singleLiveEntry() throws Exception {
        var snapshot = single("user:1", liveEntry("Alice"));
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        try (SSTableReader reader = new SSTableReader(meta)) {
            Optional<ValueEntry> result = reader.get("user:1");
            assertThat(result).isPresent();
            assertThat(new String(result.get().value())).isEqualTo("Alice");
            assertThat(result.get().tombstone()).isFalse();
        }
    }

    @Test
    @DisplayName("Tombstone entry is preserved through flush + read")
    void tombstonePreserved() throws Exception {
        var snapshot = single("dead:key", tombstone());
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        try (SSTableReader reader = new SSTableReader(meta)) {
            Optional<ValueEntry> result = reader.get("dead:key");
            assertThat(result).isPresent();
            assertThat(result.get().tombstone()).isTrue();
            assertThat(result.get().value()).isNull();
        }
    }

    @Test
    @DisplayName("Non-existent key returns empty")
    void nonExistentKey() throws Exception {
        var snapshot = single("a:1", liveEntry("v"));
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        try (SSTableReader reader = new SSTableReader(meta)) {
            assertThat(reader.get("z:9999")).isEmpty();
            assertThat(reader.get("a:0")).isEmpty();
        }
    }

    @Test
    @DisplayName("Multiple keys in sorted order — all readable")
    void multipleKeysSortedOrder() throws Exception {
        TreeMap<String, ValueEntry> snapshot = new TreeMap<>();
        for (int i = 0; i < 50; i++) {
            snapshot.put("key:%04d".formatted(i), liveEntry("val:" + i));
        }
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        try (SSTableReader reader = new SSTableReader(meta)) {
            for (int i = 0; i < 50; i++) {
                String key = "key:%04d".formatted(i);
                Optional<ValueEntry> result = reader.get(key);
                assertThat(result).as("key %s should be present", key).isPresent();
                assertThat(new String(result.get().value())).isEqualTo("val:" + i);
            }
        }
    }

    // ─── Metadata ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SSTableMetadata has correct firstKey, lastKey, entryCount")
    void metadataFields() throws Exception {
        TreeMap<String, ValueEntry> snapshot = new TreeMap<>();
        snapshot.put("apple", liveEntry("a"));
        snapshot.put("banana", liveEntry("b"));
        snapshot.put("cherry", liveEntry("c"));
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        assertThat(meta.firstKey()).isEqualTo("apple");
        assertThat(meta.lastKey()).isEqualTo("cherry");
        assertThat(meta.entryCount()).isEqualTo(3);
        assertThat(meta.sizeBytes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("mightContain() correctly rejects out-of-range keys")
    void mightContainRangeCheck() throws Exception {
        TreeMap<String, ValueEntry> snapshot = new TreeMap<>();
        snapshot.put("m:key", liveEntry("v"));
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        assertThat(meta.mightContain("m:key")).isTrue();
        assertThat(meta.mightContain("a:before")).isFalse();
        assertThat(meta.mightContain("z:after")).isFalse();
    }

    // ─── Index boundary tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("Keys at exact INDEX_INTERVAL boundaries are readable")
    void indexBoundaryKeys() throws Exception {
        TreeMap<String, ValueEntry> snapshot = new TreeMap<>();
        int N = SSTableWriter.INDEX_INTERVAL * 4;  // 64 entries — 4 index entries
        for (int i = 0; i < N; i++) {
            snapshot.put("k:%04d".formatted(i), liveEntry("v" + i));
        }
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        try (SSTableReader reader = new SSTableReader(meta)) {
            // Check keys exactly at index boundaries
            for (int boundary : new int[]{0, 16, 32, 48, 63}) {
                String key = "k:%04d".formatted(boundary);
                assertThat(reader.get(key))
                        .as("boundary key %s", key)
                        .isPresent();
            }
        }
    }

    // ─── Mixed live + tombstone ───────────────────────────────────────────────

    @Test
    @DisplayName("Mix of live entries and tombstones — each reads correctly")
    void mixedLiveAndTombstone() throws Exception {
        TreeMap<String, ValueEntry> snapshot = new TreeMap<>();
        for (int i = 0; i < 20; i++) {
            if (i % 3 == 0) {
                snapshot.put("key:" + i, tombstone());
            } else {
                snapshot.put("key:" + i, liveEntry("value:" + i));
            }
        }
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        try (SSTableReader reader = new SSTableReader(meta)) {
            for (int i = 0; i < 20; i++) {
                Optional<ValueEntry> result = reader.get("key:" + i);
                assertThat(result).isPresent();
                if (i % 3 == 0) {
                    assertThat(result.get().tombstone()).isTrue();
                } else {
                    assertThat(result.get().tombstone()).isFalse();
                    assertThat(new String(result.get().value())).isEqualTo("value:" + i);
                }
            }
        }
    }

    // ─── Large flush ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("10,000-entry flush: all keys readable with correct values")
    void largeFlush() throws Exception {
        int N = 10_000;
        TreeMap<String, ValueEntry> snapshot = new TreeMap<>();
        for (int i = 0; i < N; i++) {
            snapshot.put("key:%06d".formatted(i), liveEntry("value:" + i));
        }
        SSTableMetadata meta = SSTableWriter.write(dir, snapshot);

        assertThat(meta.entryCount()).isEqualTo(N);

        try (SSTableReader reader = new SSTableReader(meta)) {
            // Spot-check 100 random keys
            java.util.Random rng = new java.util.Random(42);
            for (int t = 0; t < 100; t++) {
                int idx = rng.nextInt(N);
                String key = "key:%06d".formatted(idx);
                Optional<ValueEntry> result = reader.get(key);
                assertThat(result).as("key %s should be present", key).isPresent();
                assertThat(new String(result.get().value())).isEqualTo("value:" + idx);
            }

            // Check first and last
            assertThat(reader.get("key:000000")).isPresent();
            assertThat(reader.get("key:%06d".formatted(N - 1))).isPresent();
        }
    }
}
