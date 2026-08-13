package com.kvstore.raft;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the Raft state machine ({@link RaftNode}).
 *
 * <p>Uses an in-memory {@link RaftTransport} stub that short-circuits network
 * calls so we can deterministically exercise leader election and log replication
 * without any actual gRPC infrastructure.
 */
@DisplayName("RaftNode — state machine")
class RaftNodeTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A transport that immediately delivers calls to the target RaftNode's handler. */
    static class InMemoryTransport implements RaftTransport {
        private final Map<String, RaftNode> cluster = new ConcurrentHashMap<>();

        void register(String id, RaftNode node) { cluster.put(id, node); }

        @Override
        public long[] sendRequestVote(String peerId, long term, String candidateId,
                                      long lastLogIndex, long lastLogTerm) {
            RaftNode peer = cluster.get(peerId);
            if (peer == null) throw new IllegalStateException("Unknown peer: " + peerId);
            return peer.handleRequestVote(term, candidateId, lastLogIndex, lastLogTerm);
        }

        @Override
        public long[] sendAppendEntries(String peerId, long term, String leaderId,
                                        long prevLogIndex, long prevLogTerm,
                                        List<RaftLogEntry> entries, long leaderCommit) {
            RaftNode peer = cluster.get(peerId);
            if (peer == null) throw new IllegalStateException("Unknown peer: " + peerId);
            return peer.handleAppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
        }
    }

    private RaftNode node1, node2, node3;
    private InMemoryTransport transport;

    @BeforeEach
    void setUp() {
        transport = new InMemoryTransport();
        node1 = new RaftNode("node-1", List.of("node-2", "node-3"), transport, e -> {});
        node2 = new RaftNode("node-2", List.of("node-1", "node-3"), transport, e -> {});
        node3 = new RaftNode("node-3", List.of("node-1", "node-2"), transport, e -> {});
        transport.register("node-1", node1);
        transport.register("node-2", node2);
        transport.register("node-3", node3);
    }

    @AfterEach
    void tearDown() {
        node1.stop(); node2.stop(); node3.stop();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("All nodes start as FOLLOWER with term=0")
    void allNodesStartAsFollower() {
        assertThat(node1.role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(node2.role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(node3.role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(node1.term()).isEqualTo(0);
    }

    @Test
    @DisplayName("RequestVote — vote granted when log is up-to-date and no prior vote")
    void voteGrantedWhenLogUpToDate() {
        long[] response = node2.handleRequestVote(1, "node-1", 0, 0);
        assertThat(response[0]).isEqualTo(1);      // respondent term = 1
        assertThat(response[1]).isEqualTo(1);      // voteGranted = true
    }

    @Test
    @DisplayName("RequestVote — vote rejected for stale term")
    void voteRejectedForStaleTerm() {
        // First, advance node2's term to 3
        node2.handleAppendEntries(3, "node-3", 0, 0, Collections.emptyList(), 0);
        // Now candidate with term 2 should be rejected
        long[] response = node2.handleRequestVote(2, "node-1", 0, 0);
        assertThat(response[1]).isEqualTo(0);      // voteGranted = false
    }

    @Test
    @DisplayName("RequestVote — double vote in same term is rejected")
    void doubleVoteRejected() {
        long[] first  = node2.handleRequestVote(1, "node-1", 0, 0);
        long[] second = node2.handleRequestVote(1, "node-3", 0, 0);  // different candidate, same term
        assertThat(first[1]).isEqualTo(1);         // first vote granted
        assertThat(second[1]).isEqualTo(0);        // second rejected
    }

    @Test
    @DisplayName("AppendEntries — follower updates term and resets to FOLLOWER")
    void appendEntriesUpdatesTermAndRole() {
        // Simulate node-1 thinking it's a candidate for term 2
        node1.handleRequestVote(2, "node-1", 0, 0); // causes node-1 to bump to term 2

        // Leader at term 3 sends heartbeat
        long[] resp = node1.handleAppendEntries(3, "node-2", 0, 0, Collections.emptyList(), 0);
        assertThat(resp[0]).isEqualTo(3);          // node-1 adopts term 3
        assertThat(resp[1]).isEqualTo(1);          // success
        assertThat(node1.role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(node1.term()).isEqualTo(3);
    }

    @Test
    @DisplayName("AppendEntries — rejected when prevLogIndex doesn't match")
    void appendEntriesRejectedOnMismatch() {
        // Follower's log only has sentinel (index=0); asking for prevLogIndex=5 should fail
        long[] resp = node2.handleAppendEntries(1, "node-1", 5, 1, Collections.emptyList(), 0);
        assertThat(resp[1]).isEqualTo(0);          // success = false
    }

    @Test
    @DisplayName("RaftLog — append and lastIndex")
    void raftLogAppendAndQuery() {
        RaftLog log = new RaftLog();
        assertThat(log.lastIndex()).isEqualTo(0);  // sentinel only

        log.append(1, "hello".getBytes(), "cmd-1");
        assertThat(log.lastIndex()).isEqualTo(1);

        log.append(1, "world".getBytes(), "cmd-2");
        assertThat(log.lastIndex()).isEqualTo(2);
        assertThat(log.get(2).commandId()).isEqualTo("cmd-2");
    }

    @Test
    @DisplayName("RaftLog — appendFromLeader consistency check")
    void raftLogConsistencyCheck() {
        RaftLog log = new RaftLog();
        log.append(1, "a".getBytes(), "a");   // index=1, term=1

        // Correct prevLogIndex/Term — should succeed
        RaftLogEntry e2 = new RaftLogEntry(1, 2, "b".getBytes(), "b");
        boolean ok = log.appendFromLeader(1, 1, List.of(e2));
        assertThat(ok).isTrue();
        assertThat(log.lastIndex()).isEqualTo(2);

        // Wrong prevLogTerm — should fail
        RaftLogEntry e3 = new RaftLogEntry(2, 3, "c".getBytes(), "c");
        boolean fail = log.appendFromLeader(2, 99, List.of(e3));  // prevLogTerm=99 but actual is 1
        assertThat(fail).isFalse();
    }
}
