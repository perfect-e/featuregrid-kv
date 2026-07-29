package com.kvstore.engine.wal;

import java.util.Arrays;

/**
 * An immutable record of a single operation written to the WAL.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code operation == PUT}    → {@code value} is non-null, non-empty.</li>
 *   <li>{@code operation == DELETE} → {@code value} is {@code null}.</li>
 * </ul>
 *
 * <p>Records are produced by {@link WalWriter} and consumed by {@link WalReader}
 * during crash recovery inside {@link com.kvstore.engine.LsmStorageEngine}.
 */
public record WalEntry(WalOperation operation, String key, byte[] value) {

    /** Compact constructor — validates invariants at construction time. */
    public WalEntry {
        if (operation == null)      throw new IllegalArgumentException("operation must not be null");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        if (operation == WalOperation.PUT && value == null)
            throw new IllegalArgumentException("PUT entry must have a non-null value");
        if (operation == WalOperation.DELETE && value != null)
            throw new IllegalArgumentException("DELETE entry must have null value");
    }

    // ─── Factory methods ─────────────────────────────────────────────────────

    public static WalEntry put(String key, byte[] value) {
        return new WalEntry(WalOperation.PUT, key, value);
    }

    public static WalEntry delete(String key) {
        return new WalEntry(WalOperation.DELETE, key, null);
    }

    // ─── Equals / hashCode / toString ────────────────────────────────────────
    // Record auto-generates these, but byte[] needs special handling.

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WalEntry other)) return false;
        return operation == other.operation
                && key.equals(other.key)
                && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        int result = operation.hashCode();
        result = 31 * result + key.hashCode();
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }

    @Override
    public String toString() {
        return switch (operation) {
            case PUT    -> "WalEntry[PUT key='%s' valueLen=%d]".formatted(key, value.length);
            case DELETE -> "WalEntry[DELETE key='%s']".formatted(key);
        };
    }
}
