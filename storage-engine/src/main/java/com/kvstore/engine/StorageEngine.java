package com.kvstore.engine;

import java.util.Optional;

/**
 * Core storage engine abstraction.
 *
 * <p>Backed by {@code LsmStorageEngine} (WAL + Memtable + SSTables + Compaction).
 *
 * <p>All implementations must be thread-safe.
 */
public interface StorageEngine extends AutoCloseable {

    /**
     * Stores a key-value pair. Overwrites any existing value.
     *
     * @param key   Non-null, non-blank key.
     * @param value Non-null value bytes.
     * @throws IllegalArgumentException if key is null/blank or value is null.
     */
    void put(String key, byte[] value);

    /**
     * Retrieves the entry for the given key.
     *
     * @param key Key to look up.
     * @return Empty if the key does not exist or was deleted.
     */
    Optional<ValueEntry> get(String key);

    /**
     * Marks a key as deleted by writing a tombstone.
     * Subsequent reads return empty until the key is written again.
     * Idempotent — deleting a non-existent key is a no-op.
     *
     * @param key Key to delete.
     */
    void delete(String key);

    /**
     * Returns the approximate count of live (non-deleted) keys.
     * May be slightly stale in concurrent environments.
     */
    long size();

    /**
     * Releases all resources held by this engine.
     * After close, any method call result is undefined.
     */
    @Override
    void close() throws Exception;
}
