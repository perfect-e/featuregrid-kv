package com.kvstore.node;

import com.kvstore.engine.LsmStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.node.config.NodeProperties;
import com.kvstore.raft.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.SmartLifecycle;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for the KV storage node.
 *
 * <p>Each node is an independent storage unit that:
 * <ol>
 *   <li>Owns a slice of the key-space (determined by the coordinator's hash ring).</li>
 *   <li>Exposes a gRPC {@code KvService} for direct Put / Get / Delete operations.</li>
 *   <li>Runs a {@link RaftNode} for leader election and log replication.</li>
 *   <li>Exposes an HTTP actuator endpoint for health checks and Prometheus metrics.</li>
 * </ol>
 *
 * <p><b>Storage:</b> backed by {@link LsmStorageEngine} (WAL + SkipList memtable + SSTables).<br>
 * <b>Consensus:</b> Raft log commits drive all writes via a {@link RaftCommand} applier.
 */
@SpringBootApplication
@EnableConfigurationProperties(NodeProperties.class)
public class NodeApplication {

    private static final Logger log = LoggerFactory.getLogger(NodeApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NodeApplication.class, args);
    }

    // ─── Storage Engine ───────────────────────────────────────────────────────

    /**
     * LSM-tree storage engine — WAL-first writes, SkipList memtable,
     * automatic SSTable flush. Crash recovery replays WAL on startup.
     */
    @Bean
    public StorageEngine storageEngine(NodeProperties props) {
        Path dataDir = Path.of(props.dataDir());
        long maxBytes = (long) props.memtableMaxSizeMb() * 1024 * 1024;
        log.info("Initializing LsmStorageEngine for node '{}' at '{}' (memtable {}MB)",
                props.id(), dataDir.toAbsolutePath(), props.memtableMaxSizeMb());
        return new LsmStorageEngine(dataDir, maxBytes);
    }

    // ─── Raft Transport ───────────────────────────────────────────────────────

    /**
     * gRPC transport for Raft peer-to-peer RPCs (RequestVote + AppendEntries).
     * Peer addresses are loaded from {@code kv.node.raft-peers} config.
     */
    @Bean
    public GrpcRaftTransport grpcRaftTransport(NodeProperties props) {
        var peers = props.raftPeerAddresses();
        log.info("Raft peers for node '{}': {}", props.id(), peers);
        return new GrpcRaftTransport(peers);
    }

    // ─── Raft Node ────────────────────────────────────────────────────────────

    /**
     * The Raft state machine for this node.
     *
     * <p>The {@code stateMachineApplier} callback is the bridge between Raft
     * and the storage engine: when a log entry is committed by a majority of
     * nodes, its {@link RaftCommand} is deserialized and applied to
     * {@link LsmStorageEngine}.
     *
     * <p>Wrapped in a {@link SmartLifecycle} bean so Spring calls
     * {@code start()} after all other beans are ready and {@code stop()}
     * on application shutdown.
     */
    @Bean
    public RaftNode raftNode(NodeProperties props,
                             GrpcRaftTransport transport,
                             StorageEngine storageEngine) {
        List<String> peerIds = List.copyOf(props.raftPeerAddresses().keySet());

        return new RaftNode(
                props.id(),
                peerIds,
                transport,
                Path.of(props.dataDir()),
                // ── State machine applier: Raft commit → LSM write ──
                entry -> {
                    if (entry.command().length == 0) return;  // noop sentinel
                    try {
                        RaftCommand cmd = RaftCommand.fromBytes(entry.command());
                        switch (cmd.type()) {
                            case PUT -> {
                                if (storageEngine instanceof LsmStorageEngine lsm && cmd.ttlMs() > 0) {
                                    lsm.put(cmd.key(), cmd.valueBytes(), cmd.ttlMs());
                                } else {
                                    storageEngine.put(cmd.key(), cmd.valueBytes());
                                }
                                log.debug("[raft-apply] PUT key='{}' term={} idx={}",
                                        cmd.key(), entry.term(), entry.index());
                            }
                            case DELETE -> {
                                storageEngine.delete(cmd.key());
                                log.debug("[raft-apply] DELETE key='{}' term={} idx={}",
                                        cmd.key(), entry.term(), entry.index());
                            }
                        }
                    } catch (Exception e) {
                        log.error("[raft-apply] Failed to apply entry idx={}: {}",
                                entry.index(), e.getMessage(), e);
                    }
                }
        );
    }

    @Bean
    public io.grpc.Server raftGrpcServer(NodeProperties props, RaftNode raftNode) {
        log.info("Creating Raft gRPC server on port {}", props.raftPort());
        return io.grpc.ServerBuilder.forPort(props.raftPort())
                .addService(new RaftServiceGrpcImpl(raftNode))
                .build();
    }

    /**
     * Lifecycle bean that starts/stops the {@link RaftNode} alongside Spring context.
     * Using {@link SmartLifecycle} ensures Raft starts after gRPC server is ready.
     */
    @Bean
    public SmartLifecycle raftLifecycle(RaftNode raftNode, GrpcRaftTransport transport, io.grpc.Server raftGrpcServer) {
        return new SmartLifecycle() {
            private volatile boolean running = false;

            @Override
            public void start() {
                try {
                    raftGrpcServer.start();
                    log.info("Raft gRPC server started on port {}", raftGrpcServer.getPort());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Failed to start Raft gRPC server", e);
                }
                raftNode.start();
                running = true;
                log.info("RaftNode started — participating in leader election.");
            }

            @Override
            public void stop() {
                raftNode.stop();
                raftGrpcServer.shutdown();
                transport.shutdown();
                running = false;
                log.info("RaftNode stopped.");
            }

            @Override public boolean isRunning()     { return running; }
            @Override public int    getPhase()        { return Integer.MAX_VALUE; } // start last
            @Override public boolean isAutoStartup()  { return true; }
        };
    }

}
