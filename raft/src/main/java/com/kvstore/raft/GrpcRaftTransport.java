package com.kvstore.raft;

import com.kvstore.raft.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link RaftTransport} implementation using gRPC.
 *
 * <h2>Channel management</h2>
 * <p>One {@link ManagedChannel} is maintained per peer node and reused across
 * calls. Channels are lazily created on first use and shut down when
 * {@link #shutdown()} is called (on node stop).
 *
 * <h2>Timeout</h2>
 * <p>Each RPC has a hard 200 ms deadline. This is deliberately shorter than the
 * Raft election timeout floor (150 ms) so a slow peer doesn't hold up an
 * election round indefinitely.
 *
 * <h2>Thread safety</h2>
 * <p>{@link ManagedChannel} and the generated stubs are thread-safe. The channel
 * map itself is a {@link ConcurrentHashMap} so multiple election threads can
 * safely reach different peers simultaneously.
 */
public class GrpcRaftTransport implements RaftTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcRaftTransport.class);
    private static final int RPC_TIMEOUT_MS = 200;

    /**
     * Map of peerId → "host:port" used for building gRPC channels.
     * Example: {@code {"node-2" → "localhost:9182", "node-3" → "localhost:9183"}}.
     */
    private final Map<String, String> peerAddresses;

    /** Lazily-created gRPC channels, one per peer. */
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    /**
     * @param peerAddresses map of nodeId → "host:raftPort" for every peer.
     */
    public GrpcRaftTransport(Map<String, String> peerAddresses) {
        this.peerAddresses = Map.copyOf(peerAddresses);
        log.info("GrpcRaftTransport initialised with peers: {}", peerAddresses);
    }

    // ─── RaftTransport implementation ────────────────────────────────────────

    @Override
    public long[] sendRequestVote(String peerId,
                                  long term,
                                  String candidateId,
                                  long lastLogIndex,
                                  long lastLogTerm) throws Exception {
        RaftServiceGrpc.RaftServiceBlockingStub stub = stubFor(peerId);
        RequestVoteRequest req = RequestVoteRequest.newBuilder()
                .setTerm(term)
                .setCandidateId(candidateId)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        log.debug("→ RequestVote to {}: term={} candidateId={}", peerId, term, candidateId);
        RequestVoteResponse resp = stub
                .withDeadlineAfter(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .requestVote(req);

        log.debug("← RequestVote from {}: term={} granted={}", peerId, resp.getTerm(), resp.getVoteGranted());
        return new long[]{ resp.getTerm(), resp.getVoteGranted() ? 1 : 0 };
    }

    @Override
    public long[] sendAppendEntries(String peerId,
                                    long term,
                                    String leaderId,
                                    long prevLogIndex,
                                    long prevLogTerm,
                                    List<RaftLogEntry> entries,
                                    long leaderCommit) throws Exception {
        RaftServiceGrpc.RaftServiceBlockingStub stub = stubFor(peerId);

        AppendEntriesRequest.Builder reqBuilder = AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId(leaderId)
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .setLeaderCommit(leaderCommit);

        // Convert domain RaftLogEntry → proto LogEntry
        for (RaftLogEntry e : entries) {
            reqBuilder.addEntries(com.kvstore.raft.proto.LogEntry.newBuilder()
                    .setTerm(e.term())
                    .setIndex(e.index())
                    .setCommand(com.google.protobuf.ByteString.copyFrom(e.command()))
                    .setCommandId(e.commandId())
                    .build());
        }

        log.debug("→ AppendEntries to {}: term={} entries={} leaderCommit={}",
                peerId, term, entries.size(), leaderCommit);
        AppendEntriesResponse resp = stub
                .withDeadlineAfter(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .appendEntries(reqBuilder.build());

        log.debug("← AppendEntries from {}: term={} success={} matchIndex={}",
                peerId, resp.getTerm(), resp.getSuccess(), resp.getMatchIndex());
        return new long[]{ resp.getTerm(), resp.getSuccess() ? 1 : 0, resp.getMatchIndex() };
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Gracefully shuts down all open gRPC channels.
     * Call on node stop to free OS resources.
     */
    public void shutdown() {
        channels.values().forEach(ch -> {
            try {
                ch.shutdown().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        channels.clear();
        log.info("GrpcRaftTransport channels closed.");
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private RaftServiceGrpc.RaftServiceBlockingStub stubFor(String peerId) {
        ManagedChannel ch = channels.computeIfAbsent(peerId, id -> {
            String addr = peerAddresses.get(id);
            if (addr == null) throw new IllegalArgumentException("Unknown Raft peer: " + id);
            String[] parts = addr.split(":");
            String host = parts[0];
            int    port = Integer.parseInt(parts[1]);
            log.info("Opening Raft gRPC channel → {} at {}:{}", id, host, port);
            return ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()   // TLS would be added in production
                    .build();
        });
        return RaftServiceGrpc.newBlockingStub(ch);
    }
}
