package com.kvstore.raft;

import java.util.List;

/**
 * Transport abstraction for Raft inter-node RPCs.
 *
 * <p>{@link RaftNode} is transport-agnostic: it delegates all network calls
 * to this interface. The production implementation ({@code GrpcRaftTransport})
 * uses gRPC stubs; a test implementation can use in-memory queues.
 *
 * <p>All methods are <em>blocking</em> — they return only after the remote peer
 * responds (or throws on timeout/connection failure). Callers are responsible
 * for running these on a background thread.
 */
public interface RaftTransport {

    /**
     * Sends a {@code RequestVote} RPC to the target peer.
     *
     * @param peerId       Target node ID.
     * @param term         Candidate's current term.
     * @param candidateId  The candidate's node ID.
     * @param lastLogIndex Index of the candidate's last log entry.
     * @param lastLogTerm  Term of the candidate's last log entry.
     * @return {@code [respondentTerm, voteGranted (1=yes/0=no)]}.
     * @throws Exception on network failure or timeout.
     */
    long[] sendRequestVote(String peerId,
                           long term,
                           String candidateId,
                           long lastLogIndex,
                           long lastLogTerm) throws Exception;

    /**
     * Sends an {@code AppendEntries} RPC (heartbeat or log replication) to the target peer.
     *
     * @param peerId       Target node ID.
     * @param term         Leader's current term.
     * @param leaderId     The leader's node ID (for client redirection).
     * @param prevLogIndex Index of the entry immediately before {@code entries}.
     * @param prevLogTerm  Term of {@code prevLogIndex} entry.
     * @param entries      New log entries to replicate (empty for heartbeat).
     * @param leaderCommit Leader's current commit index.
     * @return {@code [respondentTerm, success (1/0), matchIndex]}.
     * @throws Exception on network failure or timeout.
     */
    long[] sendAppendEntries(String peerId,
                             long term,
                             String leaderId,
                             long prevLogIndex,
                             long prevLogTerm,
                             List<RaftLogEntry> entries,
                             long leaderCommit) throws Exception;
}
