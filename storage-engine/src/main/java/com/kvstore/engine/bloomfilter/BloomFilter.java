package com.kvstore.engine.bloomfilter;

import java.util.BitSet;

/**
 * A space-efficient probabilistic data structure that answers "definitely NOT in set"
 * in O(1) time. Used to skip SSTable disk reads for keys that provably don't exist.
 *
 * <h2>How it works (for DSA context)</h2>
 * <pre>
 *   BitSet of m bits (all zeroes initially)
 *
 *   INSERT("user:1"):
 *     hash1("user:1") % m → bit 3  → set to 1
 *     hash2("user:1") % m → bit 17 → set to 1
 *     hash3("user:1") % m → bit 42 → set to 1
 *
 *   QUERY("user:1"):
 *     hash1("user:1") % m → bit 3  → is 1? YES
 *     hash2("user:1") % m → bit 17 → is 1? YES
 *     hash3("user:1") % m → bit 42 → is 1? YES
 *     → "Might be present" (check disk)
 *
 *   QUERY("user:999"):
 *     hash1("user:999") % m → bit 7  → is 1? NO
 *     → "Definitely NOT present" (skip disk I/O)
 * </pre>
 *
 * <h2>False positive rate</h2>
 * <p>With {@code n} inserted keys, {@code m} bits, {@code k} hash functions:
 * <pre>
 *   Optimal k = (m/n) * ln(2)
 *   Optimal m = -(n * ln(p)) / (ln(2))^2     p = target false positive rate
 * </pre>
 * <p>This implementation targets 1% false positive rate by default.
 *
 * <h2>Hash functions</h2>
 * <p>We derive {@code k} independent hash functions from a single MurmurHash-like
 * seed using the "double hashing" trick:
 * <pre>
 *   hashI(x) = (hash1(x) + i * hash2(x)) % m
 * </pre>
 */
public class BloomFilter {

    /** Target false positive probability (1%). */
    public static final double DEFAULT_FPP = 0.01;

    private final BitSet  bits;
    private final int     m;      // number of bits
    private final int     k;      // number of hash functions
    private final int     n;      // expected element count (stored for serialisation)

    // ─── Construction ──────────────────────────────────────────────────────────

    /**
     * Creates a Bloom filter sized for {@code expectedKeys} with a 1% false positive rate.
     *
     * @param expectedKeys Estimated number of keys to insert.
     */
    public BloomFilter(int expectedKeys) {
        this(expectedKeys, DEFAULT_FPP);
    }

    /**
     * Creates a Bloom filter for {@code expectedKeys} at the specified false positive rate.
     *
     * @param expectedKeys number of distinct keys to be inserted.
     * @param fpp          target false positive probability (0 < fpp < 1).
     */
    public BloomFilter(int expectedKeys, double fpp) {
        this.n = Math.max(1, expectedKeys);
        // m = -(n * ln(p)) / (ln 2)^2
        this.m = (int) Math.ceil(-(n * Math.log(fpp)) / (Math.log(2) * Math.log(2)));
        // k = (m/n) * ln 2
        this.k = Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
        this.bits = new BitSet(m);
    }

    /** Deserialisation constructor — restores a filter from a saved byte array. */
    public BloomFilter(byte[] serialised, int expectedKeys) {
        this.n = expectedKeys;
        this.m = (int) Math.ceil(-(n * Math.log(DEFAULT_FPP)) / (Math.log(2) * Math.log(2)));
        this.k = Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
        this.bits = BitSet.valueOf(serialised);
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Records the key in the filter. Must be called for every key written to an SSTable.
     */
    public void add(String key) {
        long h1 = murmur64a(key, 0);
        long h2 = murmur64a(key, h1);
        for (int i = 0; i < k; i++) {
            int pos = (int) Math.abs((h1 + (long) i * h2) % m);
            bits.set(pos);
        }
    }

    /**
     * Returns {@code true} if the key <em>might</em> be present (could be a false positive),
     * or {@code false} if the key is <em>definitely not</em> in the set.
     *
     * <p>A false negative is impossible: if the key was {@link #add added}, this always returns {@code true}.
     */
    public boolean mightContain(String key) {
        long h1 = murmur64a(key, 0);
        long h2 = murmur64a(key, h1);
        for (int i = 0; i < k; i++) {
            int pos = (int) Math.abs((h1 + (long) i * h2) % m);
            if (!bits.get(pos)) return false;
        }
        return true;
    }

    /** Serialises the filter to a byte array for storage in the SSTable file. */
    public byte[] toBytes() {
        return bits.toByteArray();
    }

    public int expectedKeys() { return n; }
    public int bitCount()     { return m; }
    public int hashCount()    { return k; }

    // ─── MurmurHash64A ─────────────────────────────────────────────────────────

    /**
     * MurmurHash-64A: a well-distributed 64-bit hash.
     * Chosen because it minimises clustering (correlated bit patterns) better than
     * {@link String#hashCode} for the Bloom filter use case.
     */
    private static long murmur64a(String key, long seed) {
        byte[] data = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final long M = 0xc6a4a7935bd1e995L;
        final int R = 47;

        long h = seed ^ (data.length * M);
        int len = data.length;
        int offset = 0;

        while (len >= 8) {
            long k = getLong(data, offset);
            k *= M; k ^= k >>> R; k *= M;
            h ^= k; h *= M;
            offset += 8; len -= 8;
        }

        // Handle remaining bytes
        switch (len) {
            case 7: h ^= ((long) data[offset + 6]) << 48; // fall through
            case 6: h ^= ((long) data[offset + 5]) << 40; // fall through
            case 5: h ^= ((long) data[offset + 4]) << 32; // fall through
            case 4: h ^= ((long) data[offset + 3]) << 24; // fall through
            case 3: h ^= ((long) data[offset + 2]) << 16; // fall through
            case 2: h ^= ((long) data[offset + 1]) << 8;  // fall through
            case 1: h ^= data[offset]; h *= M;
        }

        h ^= h >>> R; h *= M; h ^= h >>> R;
        return h;
    }

    private static long getLong(byte[] b, int off) {
        return ((long)(b[off  ] & 0xFF)      ) |
               ((long)(b[off+1] & 0xFF) <<  8) |
               ((long)(b[off+2] & 0xFF) << 16) |
               ((long)(b[off+3] & 0xFF) << 24) |
               ((long)(b[off+4] & 0xFF) << 32) |
               ((long)(b[off+5] & 0xFF) << 40) |
               ((long)(b[off+6] & 0xFF) << 48) |
               ((long)(b[off+7] & 0xFF) << 56);
    }
}
