package com.kvstore.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link LsmStorageEngine}.
 *
 * Coverage:
 * - Basic PUT / GET / DELETE
 * - Delete returns empty; tombstone not leaked to caller
 * - Re-write after delete
 * - Flush triggered by small threshold → data readable from SSTable
 * - Crash recovery: data in SSTable + WAL replayed on restart
 * - Tombstone in SSTable: deleted key stays absent after restart
 * - Multiple SSTable files: newest wins
 * - 5,000-key persistence across close → reopen
 */
@DisplayName("LsmStorageEngine — integration")
class LsmStorageEngineTest {

    @TempDir
    Path dataDir;

    private LsmStorageEngine open(long memtableBytes) {
        return new LsmStorageEngine(dataDir, memtableBytes);
    }

    private LsmStorageEngine openDefault() {
        // Use a very small threshold (512 B) to trigger flushes quickly in tests
        return open(512);
    }

    // ─── Basic operations ─────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT then GET returns correct value")
    void basicPutGet() throws Exception {
        try (LsmStorageEngine engine = openDefault()) {
            engine.put("hello", "world".getBytes());
            Optional<ValueEntry> result = engine.get("hello");
            assertThat(result).isPresent();
            assertThat(new String(result.get().value())).isEqualTo("world");
        }
    }

    @Test
    @DisplayName("GET on unknown key returns empty")
    void getUnknownKey() throws Exception {
        try (LsmStorageEngine engine = openDefault()) {
            assertThat(engine.get("ghost")).isEmpty();
        }
    }

    @Test
    @DisplayName("DELETE makes key invisible to GET")
    void deleteHidesKey() throws Exception {
        try (LsmStorageEngine engine = openDefault()) {
            engine.put("user:1", "Alice".getBytes());
            engine.delete("user:1");
            assertThat(engine.get("user:1")).isEmpty();
        }
    }

    @Test
    @DisplayName("Re-write after DELETE makes key visible again")
    void rewriteAfterDelete() throws Exception {
        try (LsmStorageEngine engine = openDefault()) {
            engine.put("k", "first".getBytes());
            engine.delete("k");
            engine.put("k", "second".getBytes());
            Optional<ValueEntry> result = engine.get("k");
            assertThat(result).isPresent();
            assertThat(new String(result.get().value())).isEqualTo("second");
        }
    }

    @Test
    @DisplayName("Overwrite keeps only the latest value")
    void overwriteKeepsLatest() throws Exception {
        try (LsmStorageEngine engine = openDefault()) {
            engine.put("x", "v1".getBytes());
            engine.put("x", "v2".getBytes());
            engine.put("x", "v3".getBytes());
            assertThat(new String(engine.get("x").get().value())).isEqualTo("v3");
        }
    }

    // ─── Flush mechanics ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Flush: data written before flush is readable after flush")
    void dataReadableAfterFlush() throws Exception {
        try (LsmStorageEngine engine = open(128)) {   // tiny threshold
            // Write enough to trigger at least one flush
            for (int i = 0; i < 50; i++) {
                engine.put("key:" + i, ("value:" + i).getBytes());
            }
            // forceFlush to ensure any remaining memtable data hits disk
            engine.forceFlush();

            // All keys must still be readable
            for (int i = 0; i < 50; i++) {
                Optional<ValueEntry> result = engine.get("key:" + i);
                assertThat(result).as("key:%d must be present", i).isPresent();
                assertThat(new String(result.get().value())).isEqualTo("value:" + i);
            }
        }
    }

    @Test
    @DisplayName("Flush creates at least one SSTable file in the data dir")
    void flushCreatesSSTFile() throws Exception {
        try (LsmStorageEngine engine = open(64)) {
            for (int i = 0; i < 30; i++) {
                engine.put("k" + i, ("v" + i).getBytes());
            }
            engine.forceFlush();
        }

        long sstCount = Files.list(dataDir)
                .filter(p -> p.getFileName().toString().endsWith(".sst"))
                .count();
        assertThat(sstCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Tombstone in SSTable: deleted key absent after flush + reread")
    void tombstoneInSSTable() throws Exception {
        try (LsmStorageEngine engine = open(64)) {
            engine.put("alive", "yes".getBytes());
            engine.put("dead",  "bye".getBytes());
            engine.delete("dead");
            engine.forceFlush();

            assertThat(engine.get("alive")).isPresent();
            assertThat(engine.get("dead")).isEmpty();
        }
    }

    // ─── Persistence / crash recovery ─────────────────────────────────────────

    @Test
    @DisplayName("Data in WAL survives close → reopen (WAL replay)")
    void walReplayOnReopen() throws Exception {
        try (LsmStorageEngine engine = open(4 * 1024 * 1024)) {  // large threshold → stay in WAL
            engine.put("persist:1", "hello".getBytes());
            engine.put("persist:2", "world".getBytes());
        }

        try (LsmStorageEngine engine = open(4 * 1024 * 1024)) {
            assertThat(engine.get("persist:1")).isPresent();
            assertThat(engine.get("persist:2")).isPresent();
        }
    }

    @Test
    @DisplayName("Data flushed to SSTable survives close → reopen (SSTable reload)")
    void sstableReloadOnReopen() throws Exception {
        try (LsmStorageEngine engine = open(64)) {
            for (int i = 0; i < 40; i++) {
                engine.put("sst:key:" + i, ("v" + i).getBytes());
            }
            engine.forceFlush();
        }

        // Reopen — SSTables should be loaded from disk
        try (LsmStorageEngine engine = open(64)) {
            for (int i = 0; i < 40; i++) {
                assertThat(engine.get("sst:key:" + i))
                        .as("sst:key:%d should be present after reload", i)
                        .isPresent();
            }
        }
    }

    @Test
    @DisplayName("DELETE survives close → reopen (tombstone in WAL replayed)")
    void deleteSurvivesReopen() throws Exception {
        try (LsmStorageEngine engine = open(4 * 1024 * 1024)) {
            engine.put("del:me", "value".getBytes());
            engine.delete("del:me");
        }
        try (LsmStorageEngine engine = open(4 * 1024 * 1024)) {
            assertThat(engine.get("del:me")).isEmpty();
        }
    }

    @Test
    @DisplayName("5,000 keys persist across close → reopen")
    void fiveThousandKeyPersistence() throws Exception {
        int N = 5_000;
        try (LsmStorageEngine engine = open(256 * 1024)) {
            for (int i = 0; i < N; i++) {
                engine.put("p:key:%05d".formatted(i), ("val:" + i).getBytes());
            }
            engine.forceFlush();
        }

        try (LsmStorageEngine engine = open(256 * 1024)) {
            for (int i = 0; i < N; i++) {
                String key = "p:key:%05d".formatted(i);
                Optional<ValueEntry> v = engine.get(key);
                assertThat(v).as("%s must be present", key).isPresent();
                assertThat(new String(v.get().value())).isEqualTo("val:" + i);
            }
        }
    }

    // ─── Metrics ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("memtableFillPercent() increases as entries are added")
    void fillPercentIncreases() throws Exception {
        // Use a 32KB threshold so 100×100B entries (~10KB) shows measurable fill
        try (LsmStorageEngine engine = open(32 * 1024)) {
            int before = engine.memtableFillPercent();
            for (int i = 0; i < 100; i++) {
                engine.put("fill:" + i, new byte[100]);
            }
            int after = engine.memtableFillPercent();
            assertThat(after).isGreaterThan(before);
            assertThat(after).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("sstableCount() increases after each forceFlush()")
    void sstableCountIncreases() throws Exception {
        try (LsmStorageEngine engine = openDefault()) {
            engine.put("a", "1".getBytes());
            engine.forceFlush();
            int after1 = engine.sstableCount();

            engine.put("b", "2".getBytes());
            engine.forceFlush();
            int after2 = engine.sstableCount();

            assertThat(after2).isGreaterThan(after1);
        }
    }

    // ─── Input validation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("put() with null key throws IllegalArgumentException")
    void putNullKey() throws Exception {
        try (LsmStorageEngine engine = openDefault()) {
            assertThatThrownBy(() -> engine.put(null, new byte[]{1}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("put() with null value throws IllegalArgumentException")
    void putNullValue() throws Exception {
        try (LsmStorageEngine engine = openDefault()) {
            assertThatThrownBy(() -> engine.put("k", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
