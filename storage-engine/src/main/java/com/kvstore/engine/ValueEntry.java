package com.kvstore.engine;

import java.util.Arrays;

/**
 * Immutable record representing a stored value entry.
 *
 * <p>Every write produces a new ValueEntry with a monotonically increasing version.
 * Deletes produce a tombstone entry ({@code tombstone = true, value = null}).
 *
 * <p>Design note: {@code byte[]} inside a record does not have structural equals/hashCode,
 * but for the storage engine we compare by version/key, not by value bytes — so this is fine.
 *
 * @param value       Raw value bytes. {@code null} when this is a tombstone.
 * @param version     Monotonic version counter — incremented on every Put or Delete.
 * @param timestampMs Wall-clock time (epoch ms) when this entry was written.
 * @param tombstone   {@code true} if this entry represents a deletion.
 * @param expiryMs    Absolute epoch ms at which this entry expires. 0 = never expires.
 */
public record ValueEntry(
        byte[]  value,
        long    version,
        long    timestampMs,
        boolean tombstone,
        long    expiryMs
) {

    /**
     * Creates a live value entry with no TTL (never expires).
     */
    public static ValueEntry of(byte[] value, long version) {
        return new ValueEntry(value, version, System.currentTimeMillis(), false, 0L);
    }

    /**
     * Creates a live value entry with a TTL.
     *
     * @param value   the raw bytes to store
     * @param version monotonic version
     * @param ttlMs   time-to-live in milliseconds (0 = no expiry)
     */
    public static ValueEntry of(byte[] value, long version, long ttlMs) {
        long now      = System.currentTimeMillis();
        long expiryMs = (ttlMs > 0) ? (now + ttlMs) : 0L;
        return new ValueEntry(value, version, now, false, expiryMs);
    }

    /**
     * Creates a tombstone (deletion marker) entry.
     */
    public static ValueEntry tombstone(long version) {
        return new ValueEntry(null, version, System.currentTimeMillis(), true, 0L);
    }

    /**
     * Returns {@code true} if this entry has a TTL and that TTL has passed.
     * Always returns {@code false} for tombstones (they don't expire — they are already deleted).
     */
    public boolean isExpired() {
        if (tombstone || expiryMs == 0) return false;
        return System.currentTimeMillis() > expiryMs;
    }

    /**
     * Returns a human-readable summary (does not print raw bytes).
     */
    @Override
    public String toString() {
        if (tombstone) {
            return "ValueEntry[TOMBSTONE version=%d ts=%d]".formatted(version, timestampMs);
        }
        String ttlInfo = expiryMs > 0
                ? " expiresIn=%dms".formatted(Math.max(0, expiryMs - System.currentTimeMillis()))
                : "";
        return "ValueEntry[valueLen=%d version=%d ts=%d%s]"
                .formatted(value == null ? 0 : value.length, version, timestampMs, ttlInfo);
    }

    /**
     * Structural equality based on content, not reference.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ValueEntry other)) return false;
        return version == other.version
                && timestampMs == other.timestampMs
                && tombstone == other.tombstone
                && expiryMs == other.expiryMs
                && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(version);
        result = 31 * result + Long.hashCode(timestampMs);
        result = 31 * result + Boolean.hashCode(tombstone);
        result = 31 * result + Long.hashCode(expiryMs);
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }
}
