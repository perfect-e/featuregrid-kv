package com.kvstore.raft;

import com.kvstore.raft.proto.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Server-side gRPC handler for Raft peer-to-peer RPCs.
 *
 * <p>This class is the gRPC entry point for incoming {@code RequestVote} and
 * {@code AppendEntries} calls from peer nodes. It converts the protobuf types to
 * domain objects and delegates all actual Raft logic to the {@link RaftNode}
 * state machine — keeping the transport layer thin and testable.
 *
 * <p>Registered as a gRPC service via {@code @GrpcService} in the node's
 * Spring Boot context.
 */
public class RaftServiceGrpcImpl extends RaftServiceGrpc.RaftServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(RaftServiceGrpcImpl.class);

    private final RaftNode raftNode;

    public RaftServiceGrpcImpl(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    // ─── RequestVote ─────────────────────────────────────────────────────────

    /**
     * Handles an incoming RequestVote from a candidate peer.
     * Delegates to {@link RaftNode#handleRequestVote} and converts the result
     * back to a proto response.
     */
    @Override
    public void requestVote(RequestVoteRequest request,
                            StreamObserver<RequestVoteResponse> responseObserver) {
        log.debug("[{}] ← RequestVote from candidate={} term={}",
                raftNode.id(), request.getCandidateId(), request.getTerm());
        try {
            long[] result = raftNode.handleRequestVote(
                    request.getTerm(),
                    request.getCandidateId(),
                    request.getLastLogIndex(),
                    request.getLastLogTerm()
            );
            responseObserver.onNext(RequestVoteResponse.newBuilder()
                    .setTerm(result[0])
                    .setVoteGranted(result[1] == 1)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("[{}] RequestVote handler error: {}", raftNode.id(), e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("RequestVote handler error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    // ─── AppendEntries ────────────────────────────────────────────────────────

    /**
     * Handles an incoming AppendEntries from the leader.
     * Converts proto {@link LogEntry} messages to domain {@link RaftLogEntry}
     * records and delegates to {@link RaftNode#handleAppendEntries}.
     */
    @Override
    public void appendEntries(AppendEntriesRequest request,
                              StreamObserver<AppendEntriesResponse> responseObserver) {
        log.debug("[{}] ← AppendEntries from leader={} term={} entries={}",
                raftNode.id(), request.getLeaderId(), request.getTerm(), request.getEntriesCount());
        try {
            // Convert proto LogEntry → domain RaftLogEntry
            List<RaftLogEntry> entries = request.getEntriesList().stream()
                    .map(e -> new RaftLogEntry(
                            e.getTerm(),
                            e.getIndex(),
                            e.getCommand().toByteArray(),
                            e.getCommandId()))
                    .toList();

            long[] result = raftNode.handleAppendEntries(
                    request.getTerm(),
                    request.getLeaderId(),
                    request.getPrevLogIndex(),
                    request.getPrevLogTerm(),
                    entries,
                    request.getLeaderCommit()
            );

            responseObserver.onNext(AppendEntriesResponse.newBuilder()
                    .setTerm(result[0])
                    .setSuccess(result[1] == 1)
                    .setMatchIndex(result[2])
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("[{}] AppendEntries handler error: {}", raftNode.id(), e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("AppendEntries handler error: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}
