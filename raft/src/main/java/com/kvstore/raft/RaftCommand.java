package com.kvstore.raft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;

/**
 * A KV command that is serialised into {@link RaftLogEntry#command()} bytes
 * and applied to the state machine once the entry is committed by a majority.
 *
 * <h2>Why JSON?</h2>
 * <p>JSON is human-readable (useful for debugging the WAL) and simple to
 * deserialize without a schema registry. The performance cost is negligible
 * for a learning project; a production system would use Protobuf or Avro.
 *
 * <h2>Types</h2>
 * <ul>
 *   <li>{@code PUT}    — write key+value (optionally with TTL)</li>
 *   <li>{@code DELETE} — tombstone a key</li>
 * </ul>
 */
public record RaftCommand(
        Type   type,
        String key,
        /** Base64-encoded value bytes (null for DELETE). */
        String valueBase64,
        long   ttlMs
) {
    public enum Type { PUT, DELETE }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonCreator
    public RaftCommand(
            @JsonProperty("type")         Type   type,
            @JsonProperty("key")          String key,
            @JsonProperty("valueBase64")  String valueBase64,
            @JsonProperty("ttlMs")        long   ttlMs) {
        this.type        = type;
        this.key         = key;
        this.valueBase64 = valueBase64;
        this.ttlMs       = ttlMs;
    }

    // ─── Factory methods ──────────────────────────────────────────────────────

    public static RaftCommand put(String key, byte[] value, long ttlMs) {
        return new RaftCommand(Type.PUT, key, Base64.getEncoder().encodeToString(value), ttlMs);
    }

    public static RaftCommand delete(String key) {
        return new RaftCommand(Type.DELETE, key, null, 0);
    }

    // ─── Serialization ────────────────────────────────────────────────────────

    /** Serialize to UTF-8 JSON bytes for storage in the Raft log. */
    public byte[] toBytes() {
        try {
            return MAPPER.writeValueAsBytes(this);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize RaftCommand", e);
        }
    }

    /** Deserialize from Raft log command bytes. */
    public static RaftCommand fromBytes(byte[] bytes) {
        try {
            return MAPPER.readValue(bytes, RaftCommand.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize RaftCommand", e);
        }
    }

    /** Decode the Base64 value back to raw bytes (null-safe). */
    public byte[] valueBytes() {
        if (valueBase64 == null) return null;
        return Base64.getDecoder().decode(valueBase64);
    }
}
