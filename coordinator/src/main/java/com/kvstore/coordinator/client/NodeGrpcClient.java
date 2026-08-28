package com.kvstore.coordinator.client;

import com.kvstore.coordinator.config.NodeInfo;
import com.kvstore.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages gRPC channels and blocking stubs to each storage node.
 *
 * <p>One {@link ManagedChannel} is created per node on startup and reused for
 * the lifetime of the coordinator process. Channels are closed gracefully on
 * Spring context shutdown via {@link PreDestroy}.
 *
 * <p>Day 1–2: all requests go to a single channel selected by {@link com.kvstore.coordinator.routing.SimpleRouter}.
 * Week 3: consistent hashing will replace the routing layer.
 */
@Component
public class NodeGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(NodeGrpcClient.class);

    /** nodeId → gRPC channel to that node */
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    /** nodeId → blocking stub (cached, thread-safe) */
    private final Map<String, KvServiceGrpc.KvServiceBlockingStub> stubs = new ConcurrentHashMap<>();

    /**
     * Registers nodes and creates their gRPC channels eagerly.
     * Called by {@link com.kvstore.coordinator.CoordinatorApplication} during startup.
     *
     * @param nodes List of nodes from cluster configuration.
     */
    public void registerNodes(List<NodeInfo> nodes) {
        for (NodeInfo node : nodes) {
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(node.host(), node.grpcPort())
                    .usePlaintext()           // no TLS in Day 1-2 (add in future security phase)
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build();

            channels.put(node.id(), channel);
            stubs.put(node.id(), KvServiceGrpc.newBlockingStub(channel));
            log.info("Registered node '{}' at {}:{}", node.id(), node.host(), node.grpcPort());
        }
    }

    // ─── KV Operations ───────────────────────────────────────────────────────

    public PutResponse put(String nodeId, PutRequest request) {
        return getStub(nodeId).put(request);
    }

    public GetResponse get(String nodeId, GetRequest request) {
        return getStub(nodeId).get(request);
    }

    public DeleteResponse delete(String nodeId, DeleteRequest request) {
        return getStub(nodeId).delete(request);
    }

    public PingResponse ping(String nodeId, PingRequest request) {
        return getStub(nodeId).ping(request);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private KvServiceGrpc.KvServiceBlockingStub getStub(String nodeId) {
        KvServiceGrpc.KvServiceBlockingStub stub = stubs.get(nodeId);
        if (stub == null) {
            throw new IllegalArgumentException("Unknown node ID: '" + nodeId +
                    "'. Registered nodes: " + stubs.keySet());
        }
        return stub;
    }

    /** Returns true if the given nodeId has been registered. */
    public boolean hasNode(String nodeId) {
        return stubs.containsKey(nodeId);
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down {} gRPC node channels...", channels.size());
        channels.forEach((id, ch) -> {
            try {
                ch.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                log.debug("Channel to '{}' closed", id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ch.shutdownNow();
            }
        });
    }
}
