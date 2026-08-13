package com.kvstore.raft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The Raft replicated log — an append-only, ordered list of {@link RaftLogEntry} records.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>Index 0 holds a permanent no-op sentinel entry (term=0, command=empty).
 *       This simplifies boundary checks throughout the algorithm.</li>
 *   <li>Entries are 1-indexed: {@code entries.get(i)} has {@code index = i}.</li>
 *   <li>Once committed, entries are never removed or overwritten.</li>
 *   <li>Uncommitted entries from failed elections may be truncated when
 *       a new leader sends a conflicting {@code AppendEntries}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * A {@link ReentrantReadWriteLock} guards all mutations. Multiple concurrent
 * readers are allowed; writers hold an exclusive lock.
 */
public class RaftLog {

    private static final Logger log = LoggerFactory.getLogger(RaftLog.class);

    private final List<RaftLogEntry> entries = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock  readLock  = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
    private final Path logPath;

    public RaftLog() {
        this(null);
    }

    /** Creates a durable log backed by {@code logPath}, or an in-memory log when it is null. */
    public RaftLog(Path logPath) {
        this.logPath = logPath;
        // Sentinel entry at index 0 (no-op, term 0)
        entries.add(RaftLogEntry.noop(0));
        if (logPath != null) {
            loadFromDisk();
        }
    }

    // ─── Append ───────────────────────────────────────────────────────────────

    /**
     * Appends a new entry to the log. Called by the leader when a client command arrives.
     *
     * @return The index assigned to the new entry.
     */
    public long append(long term, byte[] command, String commandId) {
        writeLock.lock();
        try {
            long index = entries.size(); // next 1-based index
            RaftLogEntry entry = new RaftLogEntry(term, index, command.clone(), commandId);
            appendRecord(entry);
            entries.add(entry);
            log.debug("Log append: index={} term={} commandId={}", index, term, commandId);
            return index;
        } finally {
            writeLock.unlock();
        }
    }

    // ─── AppendEntries reconciliation ─────────────────────────────────────────

    /**
     * Applies entries received from the leader during an {@code AppendEntries} RPC.
     *
     * <p>Algorithm (from §5.3 of the Raft paper):
     * <ol>
     *   <li>Verify the log contains an entry at {@code prevLogIndex} with term
     *       {@code prevLogTerm}. If not, return {@code false} (consistency check failed).</li>
     *   <li>If a new entry conflicts with an existing one (same index, different term),
     *       delete the existing entry and all that follow it.</li>
     *   <li>Append any new entries not already in the log.</li>
     * </ol>
     *
     * @return {@code true} if the consistency check passed and entries were applied.
     */
    public boolean appendFromLeader(long prevLogIndex, long prevLogTerm, List<RaftLogEntry> newEntries) {
        writeLock.lock();
        try {
            // 1. Consistency check
            if (prevLogIndex > 0) {
                if (prevLogIndex >= entries.size()) {
                    log.debug("AppendEntries rejected: prevLogIndex={} beyond log size={}", prevLogIndex, entries.size());
                    return false;
                }
                if (entries.get((int) prevLogIndex).term() != prevLogTerm) {
                    log.debug("AppendEntries rejected: prevLogIndex={} term mismatch (have={} want={})",
                            prevLogIndex, entries.get((int) prevLogIndex).term(), prevLogTerm);
                    return false;
                }
            }

            // 2. Reconcile incoming entries
            boolean changed = false;
            for (RaftLogEntry incoming : newEntries) {
                int idx = (int) incoming.index();
                if (idx < entries.size()) {
                    // Conflict: same index, different term → truncate from here
                    if (entries.get(idx).term() != incoming.term()) {
                        log.warn("Log conflict at index={}: truncating from here", idx);
                        entries.subList(idx, entries.size()).clear();
                        entries.add(copyOf(incoming));
                        changed = true;
                    }
                    // If term matches, entry is already present — skip
                } else {
                    if (idx != entries.size()) {
                        log.warn("AppendEntries rejected: gap before index={}", idx);
                        return false;
                    }
                    entries.add(copyOf(incoming));
                    changed = true;
                }
            }
            if (changed) {
                rewriteToDisk();
            }
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /** The index of the last entry in the log (0 if only the sentinel exists). */
    public long lastIndex() {
        readLock.lock();
        try { return entries.size() - 1L; }
        finally { readLock.unlock(); }
    }

    /** The term of the last entry in the log (0 if only the sentinel exists). */
    public long lastTerm() {
        readLock.lock();
        try { return entries.get(entries.size() - 1).term(); }
        finally { readLock.unlock(); }
    }

    /**
     * Returns the entry at {@code index}, or {@code null} if out of range.
     */
    public RaftLogEntry get(long index) {
        readLock.lock();
        try {
            if (index < 0 || index >= entries.size()) return null;
            return entries.get((int) index);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns all entries with index strictly greater than {@code fromIndex}.
     * Used by the leader to determine which entries to send in AppendEntries.
     */
    public List<RaftLogEntry> entriesAfter(long fromIndex) {
        readLock.lock();
        try {
            int start = (int) fromIndex + 1;
            if (start >= entries.size()) return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(entries.subList(start, entries.size())));
        } finally {
            readLock.unlock();
        }
    }

    /** Total number of entries including the sentinel (so actual entries = size - 1). */
    public int size() {
        readLock.lock();
        try { return entries.size(); }
        finally { readLock.unlock(); }
    }

    private static RaftLogEntry copyOf(RaftLogEntry entry) {
        return new RaftLogEntry(entry.term(), entry.index(), entry.command().clone(), entry.commandId());
    }

    private void appendRecord(RaftLogEntry entry) {
        if (logPath == null) return;
        try {
            Files.createDirectories(logPath.toAbsolutePath().getParent());
            byte[] payload = serialise(entry);
            CRC32 crc = new CRC32();
            crc.update(payload);
            try (FileChannel channel = FileChannel.open(logPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 DataOutputStream out = new DataOutputStream(java.nio.channels.Channels.newOutputStream(channel))) {
                out.writeInt(payload.length);
                out.write(payload);
                out.writeInt((int) crc.getValue());
                out.flush();
                channel.force(true);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist Raft log entry " + entry.index(), e);
        }
    }

    private void rewriteToDisk() {
        if (logPath == null) return;
        Path tempPath = logPath.resolveSibling(logPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(logPath.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(tempPath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                 DataOutputStream out = new DataOutputStream(java.nio.channels.Channels.newOutputStream(channel))) {
                for (int i = 1; i < entries.size(); i++) {
                    writeRecord(out, entries.get(i));
                }
                out.flush();
                channel.force(true);
            }
            try {
                Files.move(tempPath, logPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, logPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rewrite Raft log", e);
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(logPath)) return;
        long validBytes = 0;
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
             DataInputStream in = new DataInputStream(java.nio.channels.Channels.newInputStream(channel))) {
            while (channel.position() < channel.size()) {
                long recordStart = channel.position();
                try {
                    int payloadLength = in.readInt();
                    if (payloadLength <= 0 || payloadLength > 64 * 1024 * 1024) break;
                    byte[] payload = in.readNBytes(payloadLength);
                    if (payload.length != payloadLength) break;
                    int expectedCrc = in.readInt();
                    CRC32 crc = new CRC32();
                    crc.update(payload);
                    if ((int) crc.getValue() != expectedCrc) break;

                    RaftLogEntry entry = deserialise(payload);
                    if (entry.index() != entries.size()) {
                        throw new IllegalStateException("Raft log index gap at " + entry.index());
                    }
                    entries.add(entry);
                    validBytes = channel.position();
                } catch (EOFException | IllegalArgumentException e) {
                    break;
                }
                if (channel.position() <= recordStart) break;
            }
            if (validBytes < channel.size()) {
                channel.truncate(validBytes);
                channel.force(true);
                log.warn("Truncated incomplete or corrupt Raft log tail at byte {}", validBytes);
            }
            log.info("Loaded {} Raft log entries from {}", entries.size() - 1, logPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Raft log from " + logPath, e);
        }
    }

    private static byte[] serialise(RaftLogEntry entry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(entry.term());
            out.writeLong(entry.index());
            byte[] commandId = entry.commandId().getBytes(StandardCharsets.UTF_8);
            out.writeInt(commandId.length);
            out.write(commandId);
            out.writeInt(entry.command().length);
            out.write(entry.command());
        }
        return bytes.toByteArray();
    }

    private static RaftLogEntry deserialise(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            long term = in.readLong();
            long index = in.readLong();
            int commandIdLength = in.readInt();
            if (commandIdLength < 0 || commandIdLength > payload.length) throw new IllegalArgumentException("Invalid command ID length");
            String commandId = new String(in.readNBytes(commandIdLength), StandardCharsets.UTF_8);
            int commandLength = in.readInt();
            if (commandLength < 0 || commandLength > payload.length) throw new IllegalArgumentException("Invalid command length");
            byte[] command = in.readNBytes(commandLength);
            if (command.length != commandLength || in.available() != 0) throw new IllegalArgumentException("Malformed Raft log record");
            return new RaftLogEntry(term, index, command, commandId);
        }
    }

    private static void writeRecord(DataOutputStream out, RaftLogEntry entry) throws IOException {
        byte[] payload = serialise(entry);
        CRC32 crc = new CRC32();
        crc.update(payload);
        out.writeInt(payload.length);
        out.write(payload);
        out.writeInt((int) crc.getValue());
    }
}
