package com.kvstore.raft;

/**
 * A single entry in the Raft replicated log.
 *
 * <p>Each entry records the Raft {@link #term} in which it was appended by the
 * leader, its position in the log ({@link #index}), and the raw command bytes
 * ({@link #command}) that the state machine should apply once committed.
 *
 * <p>Immutability: entries are never modified once written. The Raft safety
 * guarantee (Log Matching Property) relies on this.
 *
 * @param term      Raft term of the leader that created this entry.
 * @param index     1-based position in the log.
 * @param command   Serialized KV operation (PUT / DELETE payload).
 * @param commandId Client-provided idempotency key (used for deduplication).
 */
public record RaftLogEntry(
        long   term,
        long   index,
        byte[] command,
        String commandId
) {
    /** Creates a no-op entry used as the initial sentinel entry at index 0. */
    public static RaftLogEntry noop(long term) {
        return new RaftLogEntry(term, 0, new byte[0], "noop");
    }
}
