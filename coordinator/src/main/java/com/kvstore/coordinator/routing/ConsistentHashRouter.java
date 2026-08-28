package com.kvstore.coordinator.routing;

import com.kvstore.coordinator.config.NodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consistent-hash router using an MD5-based ring with virtual nodes.
 *
 * <h2>Why Consistent Hashing?</h2>
 * <p>A naive modulo hash ({@code hash(key) % N}) breaks when nodes are added or
 * removed — every key potentially remaps to a different node. Consistent hashing
 * places both keys and nodes on a 2¹²⁸-point ring. A key maps to the first
 * <em>node</em> clockwise from its position on the ring. When a node joins or
 * leaves, only the keys it "owned" need to be migrated, not the entire dataset.
 *
 * <h2>Virtual Nodes (VNodes)</h2>
 * <p>Each physical node is represented by {@value VIRTUAL_NODES_PER_NODE} virtual
 * nodes spread evenly around the ring (e.g. {@code node-1#0}, {@code node-1#1}, …).
 * This prevents hot-spots when the physical nodes are few — without VNodes,
 * a 3-node cluster would carve the ring into 3 unequal arcs depending on hash luck.
 *
 * <h2>Thread Safety</h2>
 * <p>The ring is built inside {@link #setNodes} which is called once at startup.
 * {@link #route} only reads from the {@link ConcurrentSkipListMap} and is safe
 * for concurrent callers without additional locking.
 */
@Component
public class ConsistentHashRouter {

    private static final Logger log = LoggerFactory.getLogger(ConsistentHashRouter.class);

    /** Number of virtual nodes placed on the ring per physical node. */
    public static final int VIRTUAL_NODES_PER_NODE = 150;

    /**
     * The ring: a sorted map from hash position (BigInteger 0–2¹²⁸) to the
     * NodeInfo that owns that virtual node slot.
     */
    private final ConcurrentSkipListMap<BigInteger, NodeInfo> ring = new ConcurrentSkipListMap<>();

    /** The full list of physical nodes, kept for ring introspection / replication fan-out. */
    private volatile List<NodeInfo> physicalNodes = Collections.emptyList();

    /** Node IDs that are currently blacklisted (simulated partition / failure). */
    private final Set<String> blacklisted = ConcurrentHashMap.newKeySet();

    // ─── Initialisation ───────────────────────────────────────────────────────

    /**
     * Builds the consistent-hash ring from a list of physical nodes.
     * Replaces any previously registered nodes.
     *
     * @param nodes Non-empty list of cluster nodes.
     */
    public void setNodes(List<NodeInfo> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("ConsistentHashRouter requires at least one node");
        }
        ring.clear();
        for (NodeInfo node : nodes) {
            addNodeToRing(node);
        }
        this.physicalNodes = List.copyOf(nodes);
        log.info("ConsistentHashRouter ring built: {} physical nodes × {} VNodes = {} ring slots",
                nodes.size(), VIRTUAL_NODES_PER_NODE, ring.size());
    }

    private void addNodeToRing(NodeInfo node) {
        for (int i = 0; i < VIRTUAL_NODES_PER_NODE; i++) {
            BigInteger hash = md5Hash(node.id() + "#" + i);
            ring.put(hash, node);
        }
    }

    // ─── Routing ──────────────────────────────────────────────────────────────

    /**
     * Returns the owning {@link NodeInfo} for the given key.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Hash the key to a position on the ring.</li>
     *   <li>Walk clockwise until we find a live (non-blacklisted) node's VNode.</li>
     *   <li>Wrap around to the beginning of the ring if needed.</li>
     * </ol>
     *
     * @param key The KV key being operated on.
     * @return The {@link NodeInfo} of the node that deterministically owns this key.
     * @throws IllegalStateException if all nodes are offline / blacklisted.
     */
    public NodeInfo route(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("ConsistentHashRouter: ring is empty — call setNodes() first");
        }
        BigInteger keyHash = md5Hash(key);

        // Walk clockwise from keyHash, skipping blacklisted nodes.
        NodeInfo found = firstLiveNode(ring.tailMap(keyHash, true).values());
        if (found == null) {
            // Wrap around to start of ring
            found = firstLiveNode(ring.values());
        }
        if (found == null) {
            throw new IllegalStateException("All nodes are blacklisted — no available node for key: " + key);
        }
        log.debug("route(key='{}') → node='{}'", key, found.id());
        return found;
    }

    /**
     * Returns all physical nodes that are NOT blacklisted.
     * Used by {@link com.kvstore.coordinator.replication.ReplicationService}
     * to fan-out writes to follower nodes.
     */
    public List<NodeInfo> liveNodes() {
        return physicalNodes.stream()
                .filter(n -> !blacklisted.contains(n.id()))
                .toList();
    }

    /** Returns the total number of registered physical nodes. */
    public int physicalNodeCount() {
        return physicalNodes.size();
    }

    // ─── Partition simulation ─────────────────────────────────────────────────

    public void blacklist(String nodeId) {
        blacklisted.add(nodeId);
        log.info("ConsistentHashRouter: node '{}' BLACKLISTED", nodeId);
    }

    public void unblacklist(String nodeId) {
        blacklisted.remove(nodeId);
        log.info("ConsistentHashRouter: node '{}' UN-BLACKLISTED", nodeId);
    }

    public boolean isBlacklisted(String nodeId) {
        return blacklisted.contains(nodeId);
    }

    // ─── Ring introspection (for UI / metrics) ───────────────────────────────

    /**
     * Returns a compact snapshot of the ring for serialization.
     * Each entry: {@code {position: "hex…", nodeId: "node-1"}}.
     * The list is sorted by ring position (ascending).
     */
    public List<Map<String, String>> ringSnapshot() {
        List<Map<String, String>> snapshot = new ArrayList<>(ring.size());
        ring.forEach((pos, node) ->
                snapshot.add(Map.of("position", pos.toString(16), "nodeId", node.id()))
        );
        return snapshot;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private NodeInfo firstLiveNode(Iterable<NodeInfo> candidates) {
        for (NodeInfo node : candidates) {
            if (!blacklisted.contains(node.id())) {
                return node;
            }
        }
        return null;
    }

    /**
     * Computes MD5(input) and returns the result as an unsigned 128-bit integer.
     * MD5 gives excellent uniformity on the ring; cryptographic strength is not needed here.
     */
    private static BigInteger md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest);   // signum=1 → always positive
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed by the JVM spec — this branch is unreachable.
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
