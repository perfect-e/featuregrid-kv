package com.kvstore.node.grpc;

import com.google.protobuf.ByteString;
import com.kvstore.engine.LsmStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.engine.ValueEntry;
import com.kvstore.node.config.NodeProperties;
import com.kvstore.proto.*;
import com.kvstore.raft.RaftNode;
import com.kvstore.raft.RaftRole;
import com.kvstore.raft.RaftCommand;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * gRPC implementation of {@code KvService}.
 *
 * <p>Bridges incoming gRPC calls to the {@link StorageEngine} and {@link RaftNode} for consensus.
 * Registered as a gRPC service via {@code @GrpcService} (no additional config needed).
 *
 * <p>Metrics instrumented via Micrometer:
 * <ul>
 *   <li>{@code kv_puts_total} — counter</li>
 *   <li>{@code kv_gets_total} — counter (hit vs. miss labelled separately)</li>
 *   <li>{@code kv_deletes_total} — counter</li>
 *   <li>{@code kv_operation_latency_seconds} — timer per operation type</li>
 * </ul>
 */
@GrpcService
public class KvServiceGrpcImpl extends KvServiceGrpc.KvServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(KvServiceGrpcImpl.class);

    private final StorageEngine  storageEngine;
    private final NodeProperties nodeProperties;
    private final RaftNode       raftNode;

    // ─── Metrics ─────────────────────────────────────────────────────────────
    private final Counter putsTotal;
    private final Counter getsHit;
    private final Counter getsMiss;
    private final Counter deletesTotal;
    private final Timer   putTimer;
    private final Timer   getTimer;
    private final Timer   deleteTimer;

    public KvServiceGrpcImpl(StorageEngine storageEngine,
                             NodeProperties nodeProperties,
                             RaftNode raftNode,
                             MeterRegistry meterRegistry) {
        this.storageEngine  = storageEngine;
        this.nodeProperties = nodeProperties;
        this.raftNode       = raftNode;

        String nodeTag = nodeProperties.id();
        putsTotal    = Counter.builder("kv_puts_total")
                              .tag("node", nodeTag).register(meterRegistry);
        getsHit      = Counter.builder("kv_gets_total")
                              .tag("node", nodeTag).tag("result", "hit").register(meterRegistry);
        getsMiss     = Counter.builder("kv_gets_total")
                              .tag("node", nodeTag).tag("result", "miss").register(meterRegistry);
        deletesTotal = Counter.builder("kv_deletes_total")
                              .tag("node", nodeTag).register(meterRegistry);
        putTimer     = Timer.builder("kv_operation_latency_seconds")
                            .tag("node", nodeTag).tag("op", "put").register(meterRegistry);
        getTimer     = Timer.builder("kv_operation_latency_seconds")
                            .tag("node", nodeTag).tag("op", "get").register(meterRegistry);
        deleteTimer  = Timer.builder("kv_operation_latency_seconds")
                            .tag("node", nodeTag).tag("op", "delete").register(meterRegistry);
    }

    // ─── Put ─────────────────────────────────────────────────────────────────

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
        if (raftNode.role() != RaftRole.LEADER) {
            String leaderId = raftNode.currentLeaderId();
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("NOT_LEADER:" + (leaderId != null ? leaderId : ""))
                    .asRuntimeException());
            return;
        }

        putTimer.record(() -> {
            try {
                // Build the command
                RaftCommand command = RaftCommand.put(
                        request.getKey(), 
                        request.getValue().toByteArray(), 
                        request.getTtlMs()
                );
                byte[] commandBytes = command.toBytes();
                String commandId = UUID.randomUUID().toString();

                long index = raftNode.appendCommand(commandBytes, commandId);
                if (index == -1) {
                    String leaderId = raftNode.currentLeaderId();
                    responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("NOT_LEADER:" + (leaderId != null ? leaderId : ""))
                            .asRuntimeException());
                    return;
                }

                // Wait for consensus commit
                raftNode.getCommitFuture(index)
                        .orTimeout(5, TimeUnit.SECONDS)
                        .thenAccept(v -> {
                            putsTotal.increment();
                            responseObserver.onNext(PutResponse.newBuilder()
                                    .setSuccess(true)
                                    .setMessage("OK")
                                    .build());
                            responseObserver.onCompleted();
                        })
                        .exceptionally(ex -> {
                            log.error("Consensus commit failed for index={}: {}", index, ex.getMessage());
                            responseObserver.onError(Status.INTERNAL
                                    .withDescription("Consensus write timeout or lost leadership")
                                    .asRuntimeException());
                            return null;
                        });

            } catch (Exception e) {
                log.error("PUT failed for key='{}': {}", request.getKey(), e.getMessage(), e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Internal error during PUT")
                        .asRuntimeException());
            }
        });
    }

    // ─── Get ─────────────────────────────────────────────────────────────────

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        getTimer.record(() -> {
            try {
                Optional<ValueEntry> entry = storageEngine.get(request.getKey());
                if (entry.isPresent()) {
                    getsHit.increment();
                    ValueEntry e = entry.get();
                    responseObserver.onNext(GetResponse.newBuilder()
                            .setFound(true)
                            .setValue(ByteString.copyFrom(e.value()))
                            .setVersion(e.version())
                            .setTimestampMs(e.timestampMs())
                            .build());
                } else {
                    getsMiss.increment();
                    responseObserver.onNext(GetResponse.newBuilder()
                            .setFound(false)
                            .build());
                }
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (Exception e) {
                log.error("GET failed for key='{}': {}", request.getKey(), e.getMessage(), e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Internal error during GET")
                        .asRuntimeException());
            }
        });
    }

    // ─── Delete ──────────────────────────────────────────────────────────────

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
        if (raftNode.role() != RaftRole.LEADER) {
            String leaderId = raftNode.currentLeaderId();
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("NOT_LEADER:" + (leaderId != null ? leaderId : ""))
                    .asRuntimeException());
            return;
        }

        deleteTimer.record(() -> {
            try {
                RaftCommand command = RaftCommand.delete(request.getKey());
                byte[] commandBytes = command.toBytes();
                String commandId = UUID.randomUUID().toString();

                long index = raftNode.appendCommand(commandBytes, commandId);
                if (index == -1) {
                    String leaderId = raftNode.currentLeaderId();
                    responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("NOT_LEADER:" + (leaderId != null ? leaderId : ""))
                            .asRuntimeException());
                    return;
                }

                // Wait for consensus commit
                raftNode.getCommitFuture(index)
                        .orTimeout(5, TimeUnit.SECONDS)
                        .thenAccept(v -> {
                            deletesTotal.increment();
                            responseObserver.onNext(DeleteResponse.newBuilder()
                                    .setSuccess(true)
                                    .setMessage("OK")
                                    .build());
                            responseObserver.onCompleted();
                        })
                        .exceptionally(ex -> {
                            log.error("Consensus commit failed for index={}: {}", index, ex.getMessage());
                            responseObserver.onError(Status.INTERNAL
                                    .withDescription("Consensus delete timeout or lost leadership")
                                    .asRuntimeException());
                            return null;
                        });

            } catch (Exception e) {
                log.error("DELETE failed for key='{}': {}", request.getKey(), e.getMessage(), e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Internal error during DELETE")
                        .asRuntimeException());
            }
        });
    }

    // ─── Ping ────────────────────────────────────────────────────────────────

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        log.debug("PING from sender='{}'", request.getSenderId());
        responseObserver.onNext(PingResponse.newBuilder()
                .setNodeId(nodeProperties.id())
                .setStatus("OK")
                .setTimestampMs(System.currentTimeMillis())
                .setKeyCount(storageEngine.size())
                .build());
        responseObserver.onCompleted();
    }
}
