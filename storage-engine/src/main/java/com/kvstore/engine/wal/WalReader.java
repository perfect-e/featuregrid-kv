package com.kvstore.engine.wal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Reads and replays {@link WalEntry} records from a Write-Ahead Log file.
 *
 * <h2>Recovery semantics</h2>
 * <p>Reading stops and returns all <em>complete</em> records seen so far when:
 * <ul>
 *   <li>The file ends cleanly (EOF after a full record).</li>
 *   <li>A truncated record is encountered (fewer bytes than the declared length).
 *       This happens when the JVM was killed mid-write.</li>
 *   <li>A CRC32 mismatch is detected. This indicates tail corruption — we stop
 *       at the last verified record rather than propagating corrupt data.</li>
 * </ul>
 *
 * <p>The WAL format is described in detail in {@link WalWriter}.
 */
public class WalReader {

    private static final Logger log = LoggerFactory.getLogger(WalReader.class);

    /**
     * Reads all valid {@link WalEntry} records from {@code walPath}.
     *
     * @param walPath path to the WAL file.
     * @return ordered list of all complete, checksum-verified entries;
     *         empty if the file does not exist or has no records.
     * @throws IOException if the file cannot be opened (NOT thrown for truncated/corrupt data).
     */
    public static List<WalEntry> readAll(Path walPath) throws IOException {
        List<WalEntry> entries = new ArrayList<>();

        if (!Files.exists(walPath)) {
            log.debug("WAL file not found (first start): {}", walPath);
            return entries;
        }

        long fileSize = Files.size(walPath);
        if (fileSize == 0) {
            log.debug("WAL file is empty: {}", walPath);
            return entries;
        }

        log.info("Replaying WAL from {} ({} bytes)...", walPath, fileSize);

        CRC32 crc32 = new CRC32();
        int recordCount = 0;

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(walPath.toFile()), 64 * 1024))) {

            while (true) {
                // ── Step 1: Read the 4-byte payload length prefix ──────────
                int payloadLen;
                try {
                    payloadLen = dis.readInt();
                } catch (EOFException e) {
                    // Clean EOF — all records read
                    break;
                }

                if (payloadLen <= 0 || payloadLen > 64 * 1024 * 1024 /* 64 MB sanity cap */) {
                    log.warn("WAL replay stopping: invalid payload length {} at record #{}. "
                            + "Treating as tail truncation.", payloadLen, recordCount);
                    break;
                }

                // ── Step 2: Read payload bytes ──────────────────────────────
                byte[] payload = new byte[payloadLen];
                int bytesRead = dis.read(payload, 0, payloadLen);
                if (bytesRead < payloadLen) {
                    log.warn("WAL replay stopping: truncated payload at record #{} "
                            + "(expected {} bytes, got {}). This is safe — the write was incomplete.",
                            recordCount, payloadLen, bytesRead);
                    break;
                }

                // ── Step 3: Read the 4-byte CRC32 ──────────────────────────
                int storedCrc;
                try {
                    storedCrc = dis.readInt();
                } catch (EOFException e) {
                    log.warn("WAL replay stopping: truncated CRC at record #{}.", recordCount);
                    break;
                }

                // ── Step 4: Verify checksum ─────────────────────────────────
                crc32.reset();
                crc32.update(payload);
                int computedCrc = (int) crc32.getValue();

                if (storedCrc != computedCrc) {
                    log.warn("WAL replay stopping: CRC mismatch at record #{} "
                            + "(stored=0x{}, computed=0x{}). Possible tail corruption — "
                            + "all preceding records are intact.",
                            recordCount,
                            Integer.toHexString(storedCrc),
                            Integer.toHexString(computedCrc));
                    break;
                }

                // ── Step 5: Deserialise the payload ─────────────────────────
                WalEntry entry = deserialise(payload, recordCount);
                if (entry == null) break;       // corrupt payload, stop safely

                entries.add(entry);
                recordCount++;
            }
        }

        log.info("WAL replay complete: {} valid entries recovered.", entries.size());
        return entries;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Deserialises a payload byte array into a {@link WalEntry}.
     * Returns {@code null} and logs a warning on any parse failure.
     */
    private static WalEntry deserialise(byte[] payload, int recordIndex) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {

            // operation
            byte opCode = in.readByte();
            WalOperation op = WalOperation.fromCode(opCode);

            // key
            int keyLen = in.readInt();
            if (keyLen <= 0 || keyLen > 4096) {
                log.warn("WAL record #{}: suspicious key length {}, skipping.", recordIndex, keyLen);
                return null;
            }
            byte[] keyBytes = new byte[keyLen];
            in.readFully(keyBytes);
            String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);

            // value
            int valueLen = in.readInt();
            if (valueLen == -1) {
                return WalEntry.delete(key);
            }
            if (valueLen < 0) {
                log.warn("WAL record #{}: invalid value length {}", recordIndex, valueLen);
                return null;
            }
            byte[] value = new byte[valueLen];
            in.readFully(value);
            return WalEntry.put(key, value);

        } catch (IOException e) {
            log.warn("WAL record #{}: failed to deserialise payload: {}", recordIndex, e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            log.warn("WAL record #{}: unknown operation code: {}", recordIndex, e.getMessage());
            return null;
        }
    }
}
