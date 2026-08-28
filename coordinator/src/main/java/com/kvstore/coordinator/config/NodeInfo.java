package com.kvstore.coordinator.config;

/**
 * Immutable record describing a single storage node in the cluster.
 *
 * @param id       Unique node identifier (e.g. "node-1").
 * @param host     Hostname or IP reachable by the coordinator.
 * @param grpcPort gRPC port on which the node's KvService listens.
 * @param httpPort HTTP port on which the node's REST + actuator endpoints listen.
 */
public record NodeInfo(
        String id,
        String host,
        int    grpcPort,
        int    httpPort
) {
    public NodeInfo {
        if (id   == null || id.isBlank())   throw new IllegalArgumentException("NodeInfo.id must not be blank");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("NodeInfo.host must not be blank");
        if (grpcPort <= 0 || grpcPort > 65535)
            throw new IllegalArgumentException("NodeInfo.grpcPort must be 1-65535, got: " + grpcPort);
        if (httpPort <= 0) httpPort = 8080;   // safe default
    }

    /** Human-readable "host:grpcPort" string for logging. */
    public String address() {
        return host + ":" + grpcPort;
    }

    /** HTTP base URL for polling storage state / actuator. */
    public String httpBaseUrl() {
        return "http://" + host + ":" + httpPort;
    }
}
