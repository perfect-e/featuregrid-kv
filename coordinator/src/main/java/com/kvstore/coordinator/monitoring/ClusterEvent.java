package com.kvstore.coordinator.monitoring;

import java.time.Instant;
import java.util.Map;

/**
 * A single event published to the SSE stream consumed by the dashboard.
 *
 * <p>Types:
 * <ul>
 *   <li>{@code OPERATION}   — a PUT / GET / DELETE completed (includes latency + target node)</li>
 *   <li>{@code NODE_STATUS} — a node came up, went down, or was killed/restarted</li>
 *   <li>{@code MEMTABLE}    — memtable fill % changed on a node</li>
 *   <li>{@code SST_FLUSH}   — a memtable was flushed to a new SSTable file</li>
 *   <li>{@code WAL_APPEND}  — a record was appended to the WAL</li>
 *   <li>{@code COMPACTION}  — compaction started or finished</li>
 * </ul>
 */
public record ClusterEvent(
        String type,
        String nodeId,
        String op,
        String key,
        double latencyMs,
        boolean success,
        Map<String, Object> extra,
        long timestampMs
) {
    // ─── Factories ────────────────────────────────────────────────────────────

    public static ClusterEvent operation(String nodeId, String op, String key,
                                         double latencyMs, boolean success) {
        return new ClusterEvent("OPERATION", nodeId, op, key, latencyMs, success, Map.of(), Instant.now().toEpochMilli());
    }

    public static ClusterEvent nodeStatus(String nodeId, String status, String role, long raftTerm) {
        return new ClusterEvent("NODE_STATUS", nodeId, null, null, 0, true,
                Map.of("status", status, "role", role != null ? role : "FOLLOWER", "raftTerm", raftTerm),
                Instant.now().toEpochMilli());
    }

    public static ClusterEvent memtable(String nodeId, int fillPercent, long walSizeBytes, int sstableCount) {
        return new ClusterEvent("MEMTABLE", nodeId, null, null, 0, true,
                Map.of("fillPercent", fillPercent, "walSizeBytes", walSizeBytes, "sstableCount", sstableCount), Instant.now().toEpochMilli());
    }

    public static ClusterEvent sstFlush(String nodeId, int sstableCount) {
        return new ClusterEvent("SST_FLUSH", nodeId, null, null, 0, true,
                Map.of("sstableCount", sstableCount), Instant.now().toEpochMilli());
    }
}
