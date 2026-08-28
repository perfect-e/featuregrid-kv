package com.kvstore.coordinator.replication;

import com.kvstore.coordinator.client.NodeGrpcClient;
import com.kvstore.coordinator.config.NodeInfo;
import com.kvstore.proto.DeleteRequest;
import com.kvstore.proto.PutRequest;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Fan-out replication service for full-replication writes (RF = N).
 *
 * <h2>Strategy: RF = N (Full Replication)</h2>
 * <p>Every PUT and DELETE is written to the primary node <em>synchronously</em>
 * (blocking the REST thread) for a fast acknowledgement to the client. The same
 * write is then replicated to all <em>other</em> live nodes
 * <em>asynchronously</em> (fire-and-forget) using a dedicated thread pool.
 *
 * <h2>Why not quorum?</h2>
 * <p>Quorum (W + R > N) requires coordinated read-repair and is tightly coupled
 * to the Raft consensus we are building in Week 4. Full replication is simpler
 * and correct for 3 nodes: every node holds every key, so a GET on any node
 * always returns fresh data.
 *
 * <h2>Failure handling</h2>
 * <p>Replication failures are logged as WARN but do not propagate to the client.
 * The primary write already succeeded. A future Raft-based approach will enforce
 * quorum durability.
 *
 * @deprecated Kept for reference. Replication is now fully handled via Raft log replication.
 */
@Deprecated
@Service
public class ReplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReplicationService.class);

    private final NodeGrpcClient nodeClient;

    /**
     * Dedicated thread pool for async replication fan-out.
     * Sized to the number of available processor cores so we don't starve the
     * gRPC / Spring thread pools.
     */
    private final Executor replicationPool =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));

    public ReplicationService(NodeGrpcClient nodeClient) {
        this.nodeClient = nodeClient;
    }

    // ─── PUT replication ─────────────────────────────────────────────────────

    /**
     * Replicates a PUT to all {@code followers} asynchronously.
     *
     * @param followers    List of nodes that should receive the replica write
     *                     (must NOT include the primary node that already received the write).
     * @param request      The original {@link PutRequest} to forward.
     */
    public void replicatePut(List<NodeInfo> followers, PutRequest request) {
        if (followers.isEmpty()) {
            return;
        }
        log.debug("Replicating PUT key='{}' to {} follower(s): {}",
                request.getKey(), followers.size(),
                followers.stream().map(NodeInfo::id).toList());

        for (NodeInfo follower : followers) {
            CompletableFuture.runAsync(() -> {
                try {
                    nodeClient.put(follower.id(), request);
                    log.debug("  ✓ Replicated PUT key='{}' → node='{}'", request.getKey(), follower.id());
                } catch (StatusRuntimeException e) {
                    log.warn("  ✗ Replication PUT key='{}' failed on node='{}': {}",
                            request.getKey(), follower.id(), e.getStatus());
                } catch (Exception e) {
                    log.warn("  ✗ Replication PUT key='{}' unexpected error on node='{}': {}",
                            request.getKey(), follower.id(), e.getMessage());
                }
            }, replicationPool);
        }
    }

    // ─── DELETE replication ───────────────────────────────────────────────────

    /**
     * Replicates a DELETE (tombstone) to all {@code followers} asynchronously.
     *
     * @param followers    List of nodes that should receive the tombstone.
     * @param request      The original {@link DeleteRequest} to forward.
     */
    public void replicateDelete(List<NodeInfo> followers, DeleteRequest request) {
        if (followers.isEmpty()) {
            return;
        }
        log.debug("Replicating DELETE key='{}' to {} follower(s): {}",
                request.getKey(), followers.size(),
                followers.stream().map(NodeInfo::id).toList());

        for (NodeInfo follower : followers) {
            CompletableFuture.runAsync(() -> {
                try {
                    nodeClient.delete(follower.id(), request);
                    log.debug("  ✓ Replicated DELETE key='{}' → node='{}'", request.getKey(), follower.id());
                } catch (StatusRuntimeException e) {
                    log.warn("  ✗ Replication DELETE key='{}' failed on node='{}': {}",
                            request.getKey(), follower.id(), e.getStatus());
                } catch (Exception e) {
                    log.warn("  ✗ Replication DELETE key='{}' unexpected error on node='{}': {}",
                            request.getKey(), follower.id(), e.getMessage());
                }
            }, replicationPool);
        }
    }
}
