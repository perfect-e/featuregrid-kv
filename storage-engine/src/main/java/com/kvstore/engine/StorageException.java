package com.kvstore.engine;

/**
 * Unchecked wrapper for I/O failures inside the storage engine.
 *
 * <p>The {@link StorageEngine} interface does not declare checked exceptions,
 * so any {@link java.io.IOException} from the WAL or SSTable layer is
 * wrapped here and propagated to the caller (typically the gRPC service).
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
