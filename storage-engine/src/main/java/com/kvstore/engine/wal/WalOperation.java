package com.kvstore.engine.wal;

/**
 * The two operations that can be recorded in the Write-Ahead Log.
 *
 * <p>Each WAL record carries exactly one of these. The byte code is what is
 * physically written to disk so it stays compact and forward-compatible.
 *
 * <ul>
 *   <li>{@code PUT}    — key + value bytes stored.</li>
 *   <li>{@code DELETE} — tombstone written; value field is absent on disk.</li>
 * </ul>
 */
public enum WalOperation {

    PUT((byte) 0),
    DELETE((byte) 1);

    private final byte code;

    WalOperation(byte code) {
        this.code = code;
    }

    /** Single-byte code written to the WAL file. */
    public byte code() {
        return code;
    }

    /**
     * Deserialises a byte code back to the enum constant.
     *
     * @throws IllegalArgumentException if the byte is unknown (corrupt record).
     */
    public static WalOperation fromCode(byte code) {
        return switch (code) {
            case 0  -> PUT;
            case 1  -> DELETE;
            default -> throw new IllegalArgumentException(
                    "Unknown WAL operation code: " + code + ". WAL may be corrupt.");
        };
    }
}
