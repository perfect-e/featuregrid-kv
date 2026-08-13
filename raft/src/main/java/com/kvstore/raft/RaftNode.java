package com.kvstore.raft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Core Raft state machine for a single cluster node.
 *
 * <h2>What this implements</h2>
 * <ul>
 *   <li><b>Persistent state:</b> {@code currentTerm}, {@code votedFor} (in-memory for now;
 *       §5.4 of the paper requires these to be flushed to stable storage before responding).</li>
 *   <li><b>Volatile state:</b> {@code commitIndex}, {@code lastApplied},
 *       {@code nextIndex[]} and {@code matchIndex[]} per follower (on leader).</li>
 *   <li><b>Election timer:</b> a randomised 150–300ms timeout restarted on every
 *       valid AppendEntries or vote grant. Fires a {@link #startElection()} when it expires.</li>
 *   <li><b>Heartbeat sender:</b> the leader sends an empty AppendEntries every 50ms
 *       to reset follower election timers and prevent spurious elections.</li>
 *   <li><b>RequestVote / AppendEntries handlers:</b> the two Raft RPCs.</li>
 * </ul>
 *
 * <h2>Integration</h2>
 * {@code RaftNode} is transport-agnostic. It exposes {@link #handleRequestVote}
 * and {@link #handleAppendEntries} that are called by the gRPC service impl.
 * It calls back into a {@link RaftTransport} interface to send RPCs to peers.
 * This separation makes it easy to unit-test the state machine in isolation.
 */
public class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    // ─── Tunables ─────────────────────────────────────────────────────────────
    private static final long ELECTION_TIMEOUT_MIN_MS = 150;
    private static final long ELECTION_TIMEOUT_MAX_MS = 300;
    private static final long HEARTBEAT_INTERVAL_MS   = 50;

    // ─── Identifiers ──────────────────────────────────────────────────────────
    private final String       nodeId;
    private final List<String> peerIds;          // IDs of all OTHER nodes in the cluster
    private final Path         stateFilePath;

    // ─── Persistent state (would be fsync'd to disk in production) ───────────
    private final AtomicLong          currentTerm = new AtomicLong(0);
    private volatile String           votedFor    = null;   // nodeId we voted for this term

    // ─── Volatile state ───────────────────────────────────────────────────────
    private volatile long commitIndex  = 0;   // highest log entry known to be committed
    private volatile long lastApplied  = 0;   // highest log entry applied to state machine

    // ─── Leader state (only valid when role == LEADER) ────────────────────────
    private final Map<String, Long> nextIndex  = new ConcurrentHashMap<>();  // peer → next index to send
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();  // peer → highest replicated index

    // ─── Role + term guard ────────────────────────────────────────────────────
    private final AtomicReference<RaftRole> role = new AtomicReference<>(RaftRole.FOLLOWER);
    private volatile String currentLeaderId = null;

    // ─── Log ─────────────────────────────────────────────────────────────────
    private final RaftLog raftLog;

    // ─── Timers + thread pools ────────────────────────────────────────────────
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> electionTimerFuture;
    private ScheduledFuture<?> heartbeatFuture;

    // ─── Transport + state machine callbacks ─────────────────────────────────
    private final RaftTransport transport;
    private final Consumer<RaftLogEntry> stateMachineApplier;   // called when entry is committed

    // ─── Election vote tracking ───────────────────────────────────────────────
    private final Set<String> votesReceived = ConcurrentHashMap.newKeySet();

    private final Map<Long, CompletableFuture<Void>> commitListeners = new ConcurrentHashMap<>();

    public RaftNode(String nodeId,
                    List<String> peerIds,
                    RaftTransport transport,
                    Consumer<RaftLogEntry> stateMachineApplier) {
        this(nodeId, peerIds, transport, null, stateMachineApplier);
    }

    public RaftNode(String nodeId,
                    List<String> peerIds,
                    RaftTransport transport,
                    Path dataDir,
                    Consumer<RaftLogEntry> stateMachineApplier) {
        this.nodeId              = nodeId;
        this.peerIds             = List.copyOf(peerIds);
        this.transport           = transport;
        this.stateFilePath       = dataDir != null ? dataDir.resolve("raft.state") : null;
        this.stateMachineApplier = stateMachineApplier;
        this.raftLog             = new RaftLog(dataDir != null ? dataDir.resolve("raft.log") : null);
        this.scheduler           = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        loadState();
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Starts the Raft node. Resets to FOLLOWER and arms the election timer.
     * Call this after all peer connections are established.
     */
    public synchronized void start() {
        log.info("[{}] Raft node starting. peers={}", nodeId, peerIds);
        becomeFollower(currentTerm.get(), null);
    }

    /**
     * Stops all background threads. Call on node shutdown.
     */
    public void stop() {
        scheduler.shutdownNow();
        log.info("[{}] Raft node stopped.", nodeId);
    }

    // ─── RPC handlers ─────────────────────────────────────────────────────────

    /**
     * Handles an incoming {@code RequestVote} RPC.
     *
     * <p>A vote is granted iff:
     * <ol>
     *   <li>The candidate's term is at least as large as ours.</li>
     *   <li>We haven't already voted for someone else this term.</li>
     *   <li>The candidate's log is at least as up-to-date as ours
     *       (Raft election restriction: §5.4.1).</li>
     * </ol>
     *
     * @return {@code [term, voteGranted]}
     */
    public synchronized long[] handleRequestVote(long candidateTerm,
                                                  String candidateId,
                                                  long lastLogIndex,
                                                  long lastLogTerm) {
        long myTerm = currentTerm.get();

        // If we see a higher term, immediately revert to follower
        if (candidateTerm > myTerm) {
            becomeFollower(candidateTerm, null);
            myTerm = candidateTerm;
        }

        // Reject if candidate's term is stale
        if (candidateTerm < myTerm) {
            log.debug("[{}] Rejecting vote for {} (stale term {} < {})", nodeId, candidateId, candidateTerm, myTerm);
            return new long[]{ myTerm, 0 };
        }

        // Check if we already voted this term
        boolean alreadyVotedForAnother = votedFor != null && !votedFor.equals(candidateId);
        if (alreadyVotedForAnother) {
            log.debug("[{}] Rejecting vote for {} (already voted for {})", nodeId, candidateId, votedFor);
            return new long[]{ myTerm, 0 };
        }

        // Check log up-to-date (§5.4.1)
        boolean candidateLogUpToDate =
                lastLogTerm > raftLog.lastTerm() ||
                (lastLogTerm == raftLog.lastTerm() && lastLogIndex >= raftLog.lastIndex());

        if (!candidateLogUpToDate) {
            log.debug("[{}] Rejecting vote for {} (log not up-to-date)", nodeId, candidateId);
            return new long[]{ myTerm, 0 };
        }

        // Grant vote
        votedFor = candidateId;
        saveState();
        resetElectionTimer();
        log.info("[{}] Granted vote to {} for term {}", nodeId, candidateId, myTerm);
        return new long[]{ myTerm, 1 };
    }

    /**
     * Handles an incoming {@code AppendEntries} RPC (heartbeat or log replication).
     *
     * @return {@code [term, success, matchIndex]}
     */
    public synchronized long[] handleAppendEntries(long leaderTerm,
                                                    String leaderId,
                                                    long prevLogIndex,
                                                    long prevLogTerm,
                                                    List<RaftLogEntry> entries,
                                                    long leaderCommit) {
        long myTerm = currentTerm.get();

        // Reject if leader's term is stale (split-brain protection)
        if (leaderTerm < myTerm) {
            log.debug("[{}] Rejecting AppendEntries from stale leader {} (term {} < {})",
                    nodeId, leaderId, leaderTerm, myTerm);
            return new long[]{ myTerm, 0, 0 };
        }

        // Discover higher term or revert to follower (e.g. after failed election)
        if (leaderTerm > myTerm) {
            becomeFollower(leaderTerm, leaderId);
        } else if (role.get() == RaftRole.CANDIDATE) {
            // Same term but we receive a valid leader's heartbeat → give up candidacy
            becomeFollower(leaderTerm, leaderId);
        }

        currentLeaderId = leaderId;
        resetElectionTimer();   // Valid heartbeat → reset timer

        // Apply entries to log
        boolean success = raftLog.appendFromLeader(prevLogIndex, prevLogTerm, entries);
        if (!success) {
            return new long[]{ currentTerm.get(), 0, 0 };
        }

        // Advance commitIndex and apply committed entries to state machine
        if (leaderCommit > commitIndex) {
            commitIndex = Math.min(leaderCommit, raftLog.lastIndex());
            applyCommitted();
        }

        return new long[]{ currentTerm.get(), 1, raftLog.lastIndex() };
    }

    // ─── Leader: append client command ────────────────────────────────────────

    /**
     * Called by the gRPC service when this node is leader and receives a client write.
     * Appends to the local log and fans out AppendEntries to all followers.
     *
     * @return the log index of the appended entry, or -1 if not leader.
     */
    public long appendCommand(byte[] command, String commandId) {
        if (role.get() != RaftRole.LEADER) {
            return -1;
        }
        long index = raftLog.append(currentTerm.get(), command, commandId);
        replicateToFollowers();
        return index;
    }

    /**
     * Returns the current leader ID as known by this node.
     * {@code null} if leader is unknown (e.g. just after an election).
     */
    public String currentLeaderId() {
        return role.get() == RaftRole.LEADER ? nodeId : currentLeaderId;
    }

    public RaftRole role() { return role.get(); }
    public long term()     { return currentTerm.get(); }
    public String id()     { return nodeId; }
    public long commitIndex() { return commitIndex; }
    public long logSize()  { return raftLog.lastIndex(); }

    // ─── State transitions ────────────────────────────────────────────────────

    private synchronized void becomeFollower(long term, String leaderId) {
        currentTerm.set(term);
        votedFor   = null;
        saveState();
        currentLeaderId = leaderId;
        role.set(RaftRole.FOLLOWER);
        cancelHeartbeat();
        resetElectionTimer();
        commitListeners.forEach((idx, future) ->
            future.completeExceptionally(new IllegalStateException("Lost leadership before entry was committed"))
        );
        commitListeners.clear();
        log.info("[{}] → FOLLOWER (term={}, leader={})", nodeId, term, leaderId);
    }

    private synchronized void becomeCandidate() {
        long newTerm = currentTerm.incrementAndGet();
        votedFor     = nodeId;   // vote for self
        saveState();
        role.set(RaftRole.CANDIDATE);
        votesReceived.clear();
        votesReceived.add(nodeId);
        log.info("[{}] → CANDIDATE (term={})", nodeId, newTerm);
    }

    private synchronized void becomeLeader() {
        role.set(RaftRole.LEADER);
        currentLeaderId = nodeId;
        // Initialise leader's per-follower tracking
        long nextIdx = raftLog.lastIndex() + 1;
        peerIds.forEach(p -> {
            nextIndex.put(p, nextIdx);
            matchIndex.put(p, 0L);
        });
        cancelElectionTimer();
        scheduleHeartbeat();
        log.info("[{}] ★ LEADER (term={})", nodeId, currentTerm.get());
    }

    // ─── Elections ────────────────────────────────────────────────────────────

    private synchronized void startElection() {
        if (role.get() == RaftRole.LEADER) return;
        becomeCandidate();
        long term        = currentTerm.get();
        long lastLogIdx  = raftLog.lastIndex();
        long lastLogTerm = raftLog.lastTerm();

        resetElectionTimer();   // re-arm in case we don't win this round

        for (String peerId : peerIds) {
            scheduler.submit(() -> {
                try {
                    long[] result = transport.sendRequestVote(peerId, term, nodeId, lastLogIdx, lastLogTerm);
                    handleVoteResponse(result[0], result[1] == 1, peerId);
                } catch (Exception e) {
                    log.debug("[{}] RequestVote to {} failed: {}", nodeId, peerId, e.getMessage());
                }
            });
        }
    }

    private synchronized void handleVoteResponse(long peerTerm, boolean voteGranted, String peerId) {
        if (role.get() != RaftRole.CANDIDATE) return;
        if (peerTerm > currentTerm.get()) {
            becomeFollower(peerTerm, null);
            return;
        }
        if (voteGranted) {
            votesReceived.add(peerId);
            int majority = (peerIds.size() + 1) / 2 + 1;  // total nodes = peers + self
            log.debug("[{}] Vote from {}. Total={}/{} needed", nodeId, peerId, votesReceived.size(), majority);
            if (votesReceived.size() >= majority) {
                becomeLeader();
            }
        }
    }

    // ─── Heartbeat / Log replication ─────────────────────────────────────────

    private void replicateToFollowers() {
        if (role.get() != RaftRole.LEADER) return;
        for (String peerId : peerIds) {
            scheduler.submit(() -> sendAppendEntries(peerId));
        }
    }

    private void sendAppendEntries(String peerId) {
        long myTerm  = currentTerm.get();
        long ni      = nextIndex.getOrDefault(peerId, 1L);
        long prevIdx = ni - 1;
        RaftLogEntry prevEntry = raftLog.get(prevIdx);
        long prevTerm = prevEntry != null ? prevEntry.term() : 0;
        List<RaftLogEntry> toSend = raftLog.entriesAfter(prevIdx);

        try {
            long[] result = transport.sendAppendEntries(
                    peerId, myTerm, nodeId, prevIdx, prevTerm, toSend, commitIndex);
            handleAppendEntriesResponse(peerId, result[0], result[1] == 1, result[2], toSend.size());
        } catch (Exception e) {
            log.debug("[{}] AppendEntries to {} failed: {}", nodeId, peerId, e.getMessage());
        }
    }

    private synchronized void handleAppendEntriesResponse(String peerId, long peerTerm,
                                                           boolean success, long peerMatchIndex,
                                                           int entriesSent) {
        if (role.get() != RaftRole.LEADER) return;
        if (peerTerm > currentTerm.get()) {
            becomeFollower(peerTerm, null);
            return;
        }
        if (success) {
            if (entriesSent > 0) {
                matchIndex.put(peerId, peerMatchIndex);
                nextIndex.put(peerId, peerMatchIndex + 1);
                advanceCommitIndex();
            }
        } else {
            // Follower's log didn't match; back off nextIndex and retry
            long current = nextIndex.getOrDefault(peerId, 1L);
            nextIndex.put(peerId, Math.max(1, current - 1));
        }
    }

    /**
     * Advances {@code commitIndex} to the highest index replicated to a majority.
     * §5.3: only commit entries from the current term.
     */
    private void advanceCommitIndex() {
        long lastIdx = raftLog.lastIndex();
        for (long n = lastIdx; n > commitIndex; n--) {
            RaftLogEntry entry = raftLog.get(n);
            if (entry == null || entry.term() != currentTerm.get()) continue;
            int replicatedCount = 1; // self
            for (String p : peerIds) {
                if (matchIndex.getOrDefault(p, 0L) >= n) replicatedCount++;
            }
            int majority = (peerIds.size() + 1) / 2 + 1;
            if (replicatedCount >= majority) {
                commitIndex = n;
                applyCommitted();
                break;
            }
        }
    }

    private synchronized void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLogEntry entry = raftLog.get(lastApplied);
            if (entry != null && entry.command().length > 0) {
                try {
                    stateMachineApplier.accept(entry);
                } catch (Exception e) {
                    log.error("[{}] State machine error applying index={}: {}", nodeId, lastApplied, e.getMessage());
                }
            }
            CompletableFuture<Void> future = commitListeners.remove(lastApplied);
            if (future != null) {
                future.complete(null);
            }
        }
        saveState();
    }

    /**
     * Returns a CompletableFuture that completes when the given log index is committed
     * and applied to the state machine.
     */
    public CompletableFuture<Void> getCommitFuture(long index) {
        if (lastApplied >= index) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        commitListeners.put(index, future);
        return future;
    }

    // ─── Timer management ─────────────────────────────────────────────────────

    private void resetElectionTimer() {
        cancelElectionTimer();
        long delay = ELECTION_TIMEOUT_MIN_MS +
                ThreadLocalRandom.current().nextLong(ELECTION_TIMEOUT_MAX_MS - ELECTION_TIMEOUT_MIN_MS);
        electionTimerFuture = scheduler.schedule(this::startElection, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelElectionTimer() {
        if (electionTimerFuture != null && !electionTimerFuture.isDone()) {
            electionTimerFuture.cancel(false);
        }
    }

    private void scheduleHeartbeat() {
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::replicateToFollowers, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatFuture != null && !heartbeatFuture.isDone()) {
            heartbeatFuture.cancel(false);
        }
    }

    private synchronized void saveState() {
        if (stateFilePath == null) return;
        try {
            Files.createDirectories(stateFilePath.toAbsolutePath().getParent());
            Files.writeString(
                stateFilePath, 
                currentTerm.get() + "," + (votedFor != null ? votedFor : "") + ","
                        + commitIndex + "," + lastApplied,
                StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            log.error("[{}] Failed to save Raft state to disk: {}", nodeId, e.getMessage());
        }
    }

    private synchronized void loadState() {
        if (stateFilePath == null || !Files.exists(stateFilePath)) return;
        try {
            String content = Files.readString(stateFilePath, StandardCharsets.UTF_8).trim();
            String[] parts = content.split(",", 2);
            if (parts.length >= 1) {
                currentTerm.set(Long.parseLong(parts[0]));
            }
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                votedFor = parts[1];
            }
            if (parts.length >= 3) {
                commitIndex = Math.min(Long.parseLong(parts[2]), raftLog.lastIndex());
            }
            if (parts.length >= 4) {
                lastApplied = Math.min(Long.parseLong(parts[3]), commitIndex);
            }
            log.info("[{}] Loaded persistent Raft state: term={}, votedFor={}, commitIndex={}, lastApplied={}",
                    nodeId, currentTerm.get(), votedFor, commitIndex, lastApplied);
        } catch (Exception e) {
            log.error("[{}] Failed to load Raft state from disk: {}", nodeId, e.getMessage());
        }
    }
}
