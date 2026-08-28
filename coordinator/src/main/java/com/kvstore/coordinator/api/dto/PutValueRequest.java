package com.kvstore.coordinator.api.dto;

/**
 * REST request body for PUT operations.
 *
 * <p>JSON example:
 * <pre>
 * {
 *   "value": "Shubh Sahu",
 *   "ttlMs": 0
 * }
 * </pre>
 */
public record PutValueRequest(
        /** String value to store. Internally converted to UTF-8 bytes. */
        String value,
        /** Time-to-live in milliseconds. 0 means no expiry. Not enforced in Day 1-2. */
        long   ttlMs
) {
    public PutValueRequest {
        if (value == null) {
            throw new IllegalArgumentException("'value' must not be null");
        }
    }

    // Convenience constructor with no TTL
    public PutValueRequest(String value) {
        this(value, 0L);
    }
}
