package com.kvstore.engine.wal;

import com.kvstore.engine.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Appends {@link WalEntry} records to a Write-Ahead Log file.
 *
 * <h2>On-disk record format</h2>
 * <pre>
 * ┌────────────────────────────────────────────────────┐
 * │  4 bytes  │  payload length N (big-endian int)     │
 * ├────────────────────────────────────────────────────┤
 * │  1 byte   │  operation code (0=PUT, 1=DELETE)      │
 * │  4 bytes  │  key byte-length                       │
 * │  N bytes  │  key (UTF-8)                           │
 * │  4 bytes  │  value byte-length (-1 for DELETE)     │
 * │  M bytes  │  value bytes  (M=0 for DELETE)         │
 * ├────────────────────────────────────────────────────┤
 * │  4 bytes  │  CRC32 of the payload bytes above      │
 * └────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Crash safety</h2>
 * <p>Every {@link #append} call:
 * <ol>
 *   <li>Serialises the entry to a byte array.</li>
 *   <li>Computes CRC32 of those bytes.</li>
 *   <li>Writes [length | payload | crc] atomically to the OS page cache.</li>
 *   <li>Calls {@code FileDescriptor.sync()} (equivalent to {@code fsync}) to
 *       flush the page cache to durable storage before returning.</li>
 * </ol>
 * This guarantees that any entry for which {@code append} returned without
 * throwing will survive a {@code kill -9} of the JVM process.
 *
 * <p>Thread-safe: {@code append} and {@code close} are {@code synchronized}.
 */
public class WalWriter implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WalWriter.class);

    private final FileOutputStream fos;
    private final DataOutputStream dos;
    private final CRC32 crc32 = new CRC32();
    private final Path walPath;
    private volatile boolean closed = false;

    /**
     * Opens the WAL file in <em>append</em> mode.
     * Creates the file if it does not exist; does NOT truncate existing content.
     *
     * @param walPath absolute path to the WAL log file.
     * @throws IOException if the file cannot be opened.
     */
    public WalWriter(Path walPath) throws IOException {
        this.walPath = walPath;
        // append=true → we never overwrite existing records
        this.fos = new FileOutputStream(walPath.toFile(), true);
        // 64 KB write buffer — reduces system-call overhead for small values
        this.dos = new DataOutputStream(new BufferedOutputStream(fos, 64 * 1024));
        log.info("WalWriter opened: {}", walPath);
    }

    /**
     * Serialises and appends one {@link WalEntry} to the WAL file.
     *
     * <p>The call blocks until the entry is durably written to disk
     * (via {@code FileDescriptor.sync()}).
     *
     * @throws StorageException wrapping any underlying {@link IOException}.
     */
    public synchronized void append(WalEntry entry) {
        if (closed) throw new StorageException("WalWriter is closed: " + walPath);

        try {
            // ── 1. Serialise entry to a temporary byte buffer ──────────────
            byte[] payload = serialise(entry);

            // ── 2. Compute CRC32 of the payload ────────────────────────────
            crc32.reset();
            crc32.update(payload);
            int checksum = (int) crc32.getValue();

            // ── 3. Write: [4-byte length][payload][4-byte CRC32] ───────────
            dos.writeInt(payload.length);
            dos.write(payload);
            dos.writeInt(checksum);

            // ── 4. Flush buffer to OS, then fsync to durable storage ────────
            dos.flush();
            fos.getFD().sync();                   // ← the critical crash-safety call

        } catch (IOException e) {
            throw new StorageException("WAL append failed for key='" + entry.key() + "'", e);
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            dos.flush();
            fos.getFD().sync();
            dos.close();
            log.info("WalWriter closed: {}", walPath);
        } catch (IOException e) {
            log.error("Error closing WalWriter: {}", walPath, e);
            throw e;
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Serialises a {@link WalEntry} to a compact binary byte array.
     *
     * <pre>
     *   [1 byte]  operation code
     *   [4 bytes] key byte-length
     *   [?bytes]  key (UTF-8)
     *   [4 bytes] value byte-length  (-1 means DELETE / no value)
     *   [?bytes]  value bytes        (absent for DELETE)
     * </pre>
     */
    private static byte[] serialise(WalEntry entry) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(baos);

        out.writeByte(entry.operation().code());

        byte[] keyBytes = entry.key().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(keyBytes.length);
        out.write(keyBytes);

        if (entry.operation() == WalOperation.PUT) {
            out.writeInt(entry.value().length);
            out.write(entry.value());
        } else {
            out.writeInt(-1);   // sentinel: DELETE has no value
        }

        out.flush();
        return baos.toByteArray();
    }
}
