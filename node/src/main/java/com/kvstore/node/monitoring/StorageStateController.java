package com.kvstore.node.monitoring;

import com.kvstore.engine.LsmStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.node.config.NodeProperties;
import com.kvstore.raft.RaftNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes storage engine internals for the cluster dashboard and NodeStatePoller.
 *
 * <p>{@code GET /api/v1/storage/state} returns live metrics (memtable fill %, WAL size,
 * SSTable count) plus the current Raft role and term.
 * <p>{@code POST /api/v1/storage/compact} triggers a background compaction cycle.
 */
@RestController
@RequestMapping("/api/v1/storage")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class StorageStateController {

    private final StorageEngine  storageEngine;
    private final NodeProperties nodeProperties;
    private final RaftNode       raftNode;

    public StorageStateController(StorageEngine storageEngine,
                                  NodeProperties nodeProperties,
                                  RaftNode raftNode) {
        this.storageEngine  = storageEngine;
        this.nodeProperties = nodeProperties;
        this.raftNode       = raftNode;
    }

    @GetMapping("/state")
    public Map<String, Object> getState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("nodeId", nodeProperties.id());
        state.put("status", "UP");

        // Real Raft role from the state machine — no longer hardcoded
        state.put("role",     raftNode.role().name());
        state.put("raftTerm", raftNode.term());
        state.put("isLeader", raftNode.role().name().equals("LEADER"));

        if (storageEngine instanceof LsmStorageEngine lsm) {
            state.put("memtableFillPercent", lsm.memtableFillPercent());
            state.put("walSizeBytes",        lsm.walSizeBytes());
            state.put("sstableCount",        lsm.sstableCount());
            state.put("cacheHitPercent",     lsm.cacheHitPercent());
            state.put("cacheSize",           lsm.cacheSize());
        } else {
            state.put("memtableFillPercent", 0);
            state.put("walSizeBytes",        -1L);
            state.put("sstableCount",        0);
        }

        return state;
    }

    /**
     * Manually triggers a compaction cycle on this node.
     * Called from the dashboard "Trigger Compaction" button.
     */
    @PostMapping("/compact")
    public ResponseEntity<Map<String, Object>> compact() {
        if (storageEngine instanceof LsmStorageEngine lsm) {
            lsm.triggerCompaction();
            return ResponseEntity.ok(Map.of(
                    "nodeId", nodeProperties.id(),
                    "status", "compaction_triggered"));
        }
        return ResponseEntity.ok(Map.of("status", "not_supported"));
    }

    /**
     * Exposes the deep internal state of the LSM engine (Memtable contents, SSTable lists).
     * Used by the Storage Visualizer in the UI.
     */
    @GetMapping("/debug/dump")
    public ResponseEntity<Map<String, Object>> getDebugDump() {
        if (storageEngine instanceof LsmStorageEngine lsm) {
            Map<String, Object> dump = lsm.getStorageStateDump();
            dump.put("nodeId", nodeProperties.id());
            dump.put("role",   raftNode.role().name());
            return ResponseEntity.ok(dump);
        }
        return ResponseEntity.status(501).body(Map.of(
                "error", "Not an LSM storage engine",
                "nodeId", nodeProperties.id()
        ));
    }
}
