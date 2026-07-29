package com.kvstore.engine.ttl;

import com.kvstore.engine.LsmStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Day 9: Background thread that proactively evicts expired TTL keys from the memtable.
 *
 * <h2>Why a reaper thread?</h2>
 * <pre>
 *   Without it: expired keys only disappear when a client GETs them (lazy eviction).
 *               → expired data sits in memory / on disk until compaction.
 *
 *   With it:    TtlReaper wakes every 10 seconds, scans the memtable for any entry
 *               where expiryMs > 0 && now > expiryMs, and calls engine.delete(key)
 *               which writes a tombstone. The tombstone is compacted away later.
 * </pre>
 *
 * <h2>Design tradeoffs</h2>
 * <ul>
 *   <li>Reaper only scans the <em>memtable</em> — SSTables are cleaned during compaction.</li>
 *   <li>Reaper interval is 10s — some keys may live up to 10s past their TTL (acceptable).</li>
 *   <li>Uses a single daemon thread so it cannot keep the JVM alive on shutdown.</li>
 * </ul>
 */
public class TtlReaper {

    private static final Logger log = LoggerFactory.getLogger(TtlReaper.class);

    private static final long INTERVAL_SECONDS = 10L;

    private final LsmStorageEngine engine;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    public TtlReaper(LsmStorageEngine engine) {
        this.engine    = engine;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kv-ttl-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        task = scheduler.scheduleAtFixedRate(
                this::reap,
                INTERVAL_SECONDS,
                INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        log.info("TtlReaper started (interval={}s)", INTERVAL_SECONDS);
    }

    public void stop() {
        if (task != null) task.cancel(false);
        scheduler.shutdownNow();
        log.info("TtlReaper stopped");
    }

    // ─── Core logic ───────────────────────────────────────────────────────────

    private void reap() {
        try {
            int evicted = engine.evictExpiredFromMemtable();
            if (evicted > 0) {
                log.info("TtlReaper evicted {} expired key(s) from memtable", evicted);
            } else {
                log.debug("TtlReaper: no expired keys found");
            }
        } catch (Exception e) {
            log.warn("TtlReaper cycle failed: {}", e.getMessage());
        }
    }
}
