package com.kvstore.raft;

/**
 * The three roles a Raft node can occupy at any given time.
 *
 * <p>State transitions:
 * <pre>
 *   FOLLOWER  ──[election timeout]──► CANDIDATE
 *   CANDIDATE ──[wins majority]──────► LEADER
 *   CANDIDATE ──[discovers higher term]► FOLLOWER
 *   LEADER    ──[discovers higher term]► FOLLOWER
 * </pre>
 *
 * <p>Only one node may be a LEADER per term. A CANDIDATE and a LEADER
 * are never simultaneously the same node in the same term.
 */
public enum RaftRole {
    /** Receives AppendEntries from the leader; steps up on election timeout. */
    FOLLOWER,
    /** Broadcasts RequestVote to all peers; transitions to LEADER if it wins majority. */
    CANDIDATE,
    /** Sends periodic heartbeats; replicates log entries to followers. */
    LEADER
}
