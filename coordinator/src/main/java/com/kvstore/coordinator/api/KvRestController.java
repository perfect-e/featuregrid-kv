package com.kvstore.coordinator.api;

import com.google.protobuf.ByteString;
import com.kvstore.coordinator.api.dto.GetValueResponse;
import com.kvstore.coordinator.api.dto.PutValueRequest;
import com.kvstore.coordinator.client.NodeGrpcClient;
import com.kvstore.coordinator.config.NodeInfo;
import com.kvstore.coordinator.monitoring.ClusterEvent;
import com.kvstore.coordinator.monitoring.ClusterEventBus;
import com.kvstore.coordinator.replication.ReplicationService;
import com.kvstore.coordinator.routing.ConsistentHashRouter;
import com.kvstore.proto.*;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter; // library for spring boot for counter, metrics collector allow to monitor health, traffic volumes and errors rates.
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * REST controller exposing the distributed KV operations to external clients.
 *
 * <p>Routes:
 * <pre>
 *   PUT    /api/v1/kv/{key}   — store a value
 *   GET    /api/v1/kv/{key}   — retrieve a value
 *   DELETE /api/v1/kv/{key}   — delete a key
 *   GET    /api/v1/kv/_ping   — ping all nodes (health check)
 * </pre>
 *
 * <p>The controller delegates routing decisions to {@link ConsistentHashRouter}
 * (MD5 consistent hashing with 150 virtual nodes per physical node) and actual
 * gRPC calls to {@link NodeGrpcClient}.
 *
 * <p>Every PUT and DELETE is routed to the active Raft leader node. The leader
 * appends the operation to the consensus log and replicates it to followers.
 * Once a majority quorum commits the write, the state is applied to local storage.
 * 
 * RestController : Tells spring boot that this class defines rest endpoints so it automatically serializes return values like 
 * maps, objects into json objects
 * 
 * Request mapping: sets the base url path every method inside it will start with this path(/api/v1/kv)
 */


@RestController
@RequestMapping("/api/v1/kv")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class KvRestController {

    private static final Logger log = LoggerFactory.getLogger(KvRestController.class);

    private final ConsistentHashRouter router;
    private final NodeGrpcClient       nodeClient;
    private final ClusterEventBus      eventBus;
    private final ReplicationService   replicationService;

    private final Counter coordinatorPuts;
    private final Counter coordinatorGets;
    private final Counter coordinatorDeletes;
    private final Counter coordinatorErrors;

    private volatile String lastKnownLeaderId = null;

    public KvRestController(ConsistentHashRouter router,
                            NodeGrpcClient nodeClient,
                            ClusterEventBus eventBus,
                            ReplicationService replicationService,
                            MeterRegistry meterRegistry) {
        this.router             = router;
        this.nodeClient         = nodeClient;
        this.eventBus           = eventBus;
        this.replicationService = replicationService;

        // for application monitoring and Observabilty. Tracks how many times API calls are made and how many errors occur
        // exported to promethus 
        coordinatorPuts    = Counter.builder("coordinator_puts_total").register(meterRegistry);
        coordinatorGets    = Counter.builder("coordinator_gets_total").register(meterRegistry);
        coordinatorDeletes = Counter.builder("coordinator_deletes_total").register(meterRegistry);
        coordinatorErrors  = Counter.builder("coordinator_errors_total").register(meterRegistry);
    }

    // ─── PUT /api/v1/kv/{key} ────────────────────────────────────────────────

    /**
     * Stores a key-value pair.
     *
     * <p>Request body: {@code {"value": "Shubh Sahu", "ttlMs": 0}}
     * <p>Response: {@code 200 OK {"success": true, "routedTo": "node-1"}}
     */
    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> put(
            @PathVariable String key,                                       // extract key from the url 
            @RequestBody  PutValueRequest body                              // extract value and ttl from request body
    ) {
        log.info("REST PUT key='{}'", key);
        coordinatorPuts.increment();
        long start = System.currentTimeMillis();

        PutRequest grpcRequest = PutRequest.newBuilder()
                .setKey(key)
                .setValue(ByteString.copyFrom(body.value(), StandardCharsets.UTF_8))
                .setTtlMs(body.ttlMs())
                .build();
        try {
            PutResponse response = executePutWithRetry(key, grpcRequest);

            double latency = System.currentTimeMillis() - start;
            eventBus.publish(ClusterEvent.operation(lastKnownLeaderId, "PUT", key, latency, true));

            List<String> followers = router.liveNodes().stream()
                    .map(NodeInfo::id)
                    .filter(id -> !id.equals(lastKnownLeaderId))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "success",  response.getSuccess(),
                    "routedTo", lastKnownLeaderId,
                    "replicas", followers
            ));
        } catch (Exception e) {
            coordinatorErrors.increment();
            String failedNode = lastKnownLeaderId != null ? lastKnownLeaderId : router.route(key).id();
            eventBus.publish(ClusterEvent.operation(failedNode, "PUT", key, System.currentTimeMillis() - start, false));
            log.error("gRPC PUT failed for key='{}': {}", key, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Write consensus failed: " + e.getMessage(),
                                 "routedTo", failedNode));
        }
    }

    // ─── GET /api/v1/kv/{key} ────────────────────────────────────────────────

    /**
     * Retrieves the value for a key.
     *
     * <p>Returns {@code 200} with {@code found=true} if the key exists.
     * Returns {@code 404} with {@code found=false} if the key is absent or deleted.
     */
    @GetMapping("/{key}")
    public ResponseEntity<GetValueResponse> get(@PathVariable String key) {
        log.info("REST GET key='{}'", key);
        coordinatorGets.increment();
        long start = System.currentTimeMillis();

        // GET always routes to the deterministic primary owner.
        // Since RF=N all nodes hold the key, but routing to the primary
        // keeps read path consistent with the write path.
        NodeInfo primary = router.route(key);
        try {
            GetResponse response = nodeClient.get(primary.id(), GetRequest.newBuilder()
                    .setKey(key)
                    .build());

            double latency = System.currentTimeMillis() - start;
            eventBus.publish(ClusterEvent.operation(primary.id(), "GET", key, latency, response.getFound()));

            if (response.getFound()) {
                String value = response.getValue().toString(StandardCharsets.UTF_8);
                return ResponseEntity.ok(
                        GetValueResponse.found(value, response.getVersion(),
                                               response.getTimestampMs(), primary.id()));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(GetValueResponse.notFound(primary.id()));
            }
        } catch (StatusRuntimeException e) {
            coordinatorErrors.increment();
            eventBus.publish(ClusterEvent.operation(primary.id(), "GET", key, System.currentTimeMillis() - start, false));
            log.error("gRPC GET failed for key='{}' on node='{}': {}", key, primary.id(), e.getStatus());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    // ─── DELETE /api/v1/kv/{key} ─────────────────────────────────────────────

    /**
     * Deletes a key (writes a tombstone on the owning node).
     *
     * <p>Returns {@code 200} whether or not the key previously existed.
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String key) {
        log.info("REST DELETE key='{}'", key);
        coordinatorDeletes.increment();
        long start = System.currentTimeMillis();

        DeleteRequest grpcRequest = DeleteRequest.newBuilder().setKey(key).build();
        try {
            DeleteResponse response = executeDeleteWithRetry(key, grpcRequest);

            eventBus.publish(ClusterEvent.operation(lastKnownLeaderId, "DELETE", key, System.currentTimeMillis() - start, true));

            List<String> followers = router.liveNodes().stream()
                    .map(NodeInfo::id)
                    .filter(id -> !id.equals(lastKnownLeaderId))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "success",  response.getSuccess(),
                    "routedTo", lastKnownLeaderId,
                    "replicas", followers
            ));
        } catch (Exception e) {
            coordinatorErrors.increment();
            String failedNode = lastKnownLeaderId != null ? lastKnownLeaderId : router.route(key).id();
            eventBus.publish(ClusterEvent.operation(failedNode, "DELETE", key, System.currentTimeMillis() - start, false));
            log.error("gRPC DELETE failed for key='{}': {}", key, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Delete consensus failed: " + e.getMessage(),
                                 "routedTo", failedNode));
        }
    }

    // ─── GET /api/v1/kv/_ping ────────────────────────────────────────────────

    /**
     * Pings all registered nodes and returns their status.
     * Useful for verifying cluster connectivity from a single REST call.
     */
    @GetMapping("/_ping")
    public ResponseEntity<Map<String, Object>> pingAll() {
        log.info("REST _ping all nodes");
        // Coordinator pings all nodes and collects results
        // (Week 3: will also verify ring membership)
        return ResponseEntity.ok(Map.of("status", "coordinator-ok"));
    }

    // ─── Private Helpers for Leader Write Routing ─────────────────────────────

    private PutResponse executePutWithRetry(String key, PutRequest grpcRequest) {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String target = lastKnownLeaderId;
            if (target == null || !nodeClient.hasNode(target)) {
                target = router.route(key).id();
            }
            try {
                PutResponse res = nodeClient.put(target, grpcRequest);
                lastKnownLeaderId = target; // target succeeded and is leader
                return res;
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE &&
                    e.getStatus().getDescription() != null &&
                    e.getStatus().getDescription().startsWith("NOT_LEADER:")) {
                    String leaderId = e.getStatus().getDescription().substring("NOT_LEADER:".length()).trim();
                    if (!leaderId.isEmpty()) {
                        log.info("Node '{}' returned NOT_LEADER. Updating leader to '{}' and retrying (attempt {}/{})...",
                                 target, leaderId, attempt + 1, maxRetries);
                        lastKnownLeaderId = leaderId;
                    } else {
                        log.info("Node '{}' returned NOT_LEADER with empty leader. Retrying routing (attempt {}/{})...",
                                 target, attempt + 1, maxRetries);
                        lastKnownLeaderId = null; // force recalculation via router
                    }
                } else {
                    throw e; // propagate other errors (like actual network timeout, etc.)
                }
            }
        }
        throw new IllegalStateException("Max retries exceeded looking for Raft leader");
    }

    private DeleteResponse executeDeleteWithRetry(String key, DeleteRequest grpcRequest) {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String target = lastKnownLeaderId;
            if (target == null || !nodeClient.hasNode(target)) {
                target = router.route(key).id();
            }
            try {
                DeleteResponse res = nodeClient.delete(target, grpcRequest);
                lastKnownLeaderId = target;
                return res;
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE &&
                    e.getStatus().getDescription() != null &&
                    e.getStatus().getDescription().startsWith("NOT_LEADER:")) {
                    String leaderId = e.getStatus().getDescription().substring("NOT_LEADER:".length()).trim();
                    if (!leaderId.isEmpty()) {
                        log.info("Node '{}' returned NOT_LEADER. Updating leader to '{}' and retrying (attempt {}/{})...",
                                 target, leaderId, attempt + 1, maxRetries);
                        lastKnownLeaderId = leaderId;
                    } else {
                        log.info("Node '{}' returned NOT_LEADER with empty leader. Retrying routing (attempt {}/{})...",
                                 target, attempt + 1, maxRetries);
                        lastKnownLeaderId = null;
                    }
                } else {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Max retries exceeded looking for Raft leader");
    }
}
