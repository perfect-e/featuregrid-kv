package com.kvstore.node.metrics;

import com.kvstore.engine.LsmStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.node.config.NodeProperties;
import com.kvstore.raft.RaftNode;
import com.kvstore.raft.RaftRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Day 9: Micrometer Gauge metrics for the LSM storage engine.
 *
 * <p>Registers all node-level storage metrics as Gauges against the
 * shared {@link MeterRegistry}. Spring auto-registers this as a Bean
 * and invokes {@link #registerMetrics()} after construction.
 *
 * <h2>Why Gauges for these metrics?</h2>
 * <pre>
 *   Counter  = monotonically increasing (puts, gets, compaction runs)
 *   Gauge    = current value that can go up AND down (memtable fill %, cache size)
 *   Timer    = latency distribution (already in KvServiceGrpcImpl)
 *
 *   These metrics are "snapshots" — they fluctuate:
 *     memtableFillPercent: 0 → 100% → 0% (on flush), so Gauge
 *     sstableCount:        grows on flush, shrinks on compaction, so Gauge
 *     walSizeBytes:        grows on writes, resets on flush rotation, so Gauge
 *     cacheHitPercent:     calculated ratio, so Gauge
 * </pre>
 *
 * <p>All metrics include a {@code node} tag so Grafana can filter/group by node.
 */
@Component
public class StorageMetrics {

    private final StorageEngine  storageEngine;
    private final NodeProperties nodeProperties;
    private final RaftNode       raftNode;
    private final MeterRegistry  meterRegistry;

    public StorageMetrics(StorageEngine storageEngine,
                          NodeProperties nodeProperties,
                          RaftNode raftNode,
                          MeterRegistry meterRegistry) {
        this.storageEngine  = storageEngine;
        this.nodeProperties = nodeProperties;
        this.raftNode       = raftNode;
        this.meterRegistry  = meterRegistry;
    }

    @PostConstruct
    public void registerMetrics() {
        String node = nodeProperties.id();

        // ── Raft Consensus ───────────────────────────────────────────────────
        Gauge.builder("kv.raft.term", raftNode, RaftNode::term)
             .description("Current Raft term of the node")
             .tag("node", node)
             .register(meterRegistry);

        Gauge.builder("kv.raft.commit.index", raftNode, RaftNode::commitIndex)
             .description("Current Raft commit index of the node")
             .tag("node", node)
             .register(meterRegistry);

        Gauge.builder("kv.raft.is.leader", raftNode, n -> n.role() == RaftRole.LEADER ? 1 : 0)
             .description("1 if the node is currently the Raft leader, 0 otherwise")
             .tag("node", node)
             .register(meterRegistry);

        // Only bind LSM-specific metrics if the engine is an LsmStorageEngine
        if (!(storageEngine instanceof LsmStorageEngine lsm)) return;

        // ── Memtable ──────────────────────────────────────────────────────────
        Gauge.builder("kv.memtable.fill.percent", lsm, LsmStorageEngine::memtableFillPercent)
             .description("Memtable saturation percentage (0–100). Flush triggers at 100%.")
             .tag("node", node)
             .register(meterRegistry);

        // ── SSTables ──────────────────────────────────────────────────────────
        Gauge.builder("kv.sstable.count", lsm, LsmStorageEngine::sstableCount)
             .description("Number of immutable SSTable files on disk. Grows on flush, shrinks on compaction.")
             .tag("node", node)
             .register(meterRegistry);

        // ── WAL ───────────────────────────────────────────────────────────────
        Gauge.builder("kv.wal.size.bytes", lsm, LsmStorageEngine::walSizeBytes)
             .description("Write-Ahead Log file size in bytes. Resets to 0 after each memtable flush.")
             .tag("node", node)
             .register(meterRegistry);

        // ── LRU Cache ────────────────────────────────────────────────────────
        Gauge.builder("kv.cache.hit.percent", lsm, LsmStorageEngine::cacheHitPercent)
             .description("LRU read cache hit ratio (0–100). Higher = fewer disk reads.")
             .tag("node", node)
             .register(meterRegistry);

        Gauge.builder("kv.cache.size", lsm, LsmStorageEngine::cacheSize)
             .description("Number of entries currently in the LRU read cache.")
             .tag("node", node)
             .register(meterRegistry);
    }
}
