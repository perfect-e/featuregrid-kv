package com.kvstore.coordinator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Coordinator cluster configuration — bound from {@code application.yml} under {@code kv.cluster}.
 *
 * <p>Example YAML:
 * <pre>
 * kv:
 *   cluster:
 *     nodes:
 *       - id: node-1
 *         host: localhost
 *         grpc-port: 9091
 *       - id: node-2
 *         host: localhost
 *         grpc-port: 9092
 * </pre>
 */
@ConfigurationProperties(prefix = "kv.cluster")
public record ClusterProperties(List<NodeEntry> nodes) {

    public ClusterProperties {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("kv.cluster.nodes must have at least one entry");
        }
    }

    /** Converts config entries to {@link NodeInfo} records. */
    public List<NodeInfo> nodeInfoList() {
        return nodes.stream()
                .map(e -> new NodeInfo(e.id(), e.host(), e.grpcPort(), e.httpPort() == 0 ? 8080 : e.httpPort()))
                .toList();
    }

    /**
     * A single node entry as parsed from YAML.
     * Uses a nested class so Spring Boot can bind camelCase / kebab-case properties.
     */
    public record NodeEntry(String id, String host, int grpcPort, int httpPort) {}
}
