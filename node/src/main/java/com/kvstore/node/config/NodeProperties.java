package com.kvstore.node.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Node configuration — bound from {@code application.yml} under prefix {@code kv.node}.
 *
 * <p>Example:
 * <pre>
 * kv:
 *   node:
 *     id: node-1
 *     data-dir: /data/kv
 *     memtable-max-size-mb: 4
 *     raft-port: 9181
 *     raft-peers:
 *       - node-2=localhost:9182
 *       - node-3=localhost:9183
 * </pre>
 */
@ConfigurationProperties(prefix = "kv.node")
public record NodeProperties(

        /** Unique node identifier (e.g. "node-1"). Set via NODE_ID env var. */
        String id,

        /**
         * Directory where WAL segments and SSTables are stored.
         */
        String dataDir,

        /**
         * Memtable flush threshold in megabytes.
         * Default: 4 MB.
         */
        int memtableMaxSizeMb,

        /**
         * Port on which this node's Raft gRPC server listens.
         * Default: 9181 (node-1), 9182 (node-2), 9183 (node-3).
         */
        int raftPort,

        /**
         * Addresses of peer nodes' Raft gRPC servers.
         * Format: {@code "nodeId=host:port"}, e.g. {@code "node-2=localhost:9182"}.
         */
        List<String> raftPeers
) {
    public NodeProperties {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("kv.node.id must be set");
        }
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "./data/" + id;
        }
        if (memtableMaxSizeMb <= 0) {
            memtableMaxSizeMb = 4;
        }
        if (raftPort <= 0) {
            raftPort = 9181;
        }
        if (raftPeers == null) {
            raftPeers = List.of();
        }
    }

    /**
     * Parses {@code raftPeers} into a {@code Map<nodeId, "host:port">}.
     * Used by {@link com.kvstore.raft.GrpcRaftTransport}.
     */
    public java.util.Map<String, String> raftPeerAddresses() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String peer : raftPeers) {
            String[] parts = peer.split("=", 2);
            if (parts.length == 2) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
