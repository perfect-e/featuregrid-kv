package com.kvstore.coordinator.monitoring;

import com.kvstore.coordinator.config.ClusterProperties;
import com.kvstore.coordinator.routing.ConsistentHashRouter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * REST + SSE controller for the live cluster dashboard.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/v1/monitor/events}       — SSE stream of {@link ClusterEvent}s</li>
 *   <li>{@code GET  /api/v1/monitor/state}         — point-in-time cluster snapshot</li>
 *   <li>{@code GET  /api/v1/monitor/ring}          — consistent-hash ring snapshot</li>
 *   <li>{@code POST /api/v1/monitor/nodes/{id}/kill}    — blacklist a node (simulated partition)</li>
 *   <li>{@code POST /api/v1/monitor/nodes/{id}/restart} — un-blacklist a node</li>
 * </ul>
 *
 * <p>CORS is configured to allow the Vite dev server ({@code localhost:5173}).
 */
@RestController
@RequestMapping("/api/v1/monitor")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class MonitoringController {

    private final ClusterEventBus      eventBus;
    private final ClusterProperties    clusterProperties;
    private final ConsistentHashRouter router;

    public MonitoringController(ClusterEventBus eventBus,
                                ClusterProperties clusterProperties,
                                ConsistentHashRouter router) {
        this.eventBus          = eventBus;
        this.clusterProperties = clusterProperties;
        this.router            = router;
    }

    // ─── SSE stream ───────────────────────────────────────────────────────────

    /**
     * Opens an SSE connection. The response stays open indefinitely.
     * The dashboard's {@code EventSource} reconnects automatically if dropped.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        eventBus.addEmitter(emitter);
        // Send initial node statuses so the dashboard has a starting state
        clusterProperties.nodes().forEach(node -> {
            String status = router.isBlacklisted(node.id()) ? "KILLED" : "UP";
            eventBus.publish(ClusterEvent.nodeStatus(node.id(), status, "FOLLOWER", 0L));
        });
        return emitter;
    }

    // ─── Cluster state snapshot ────────────────────────────────────────────────

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> state() {
        return ResponseEntity.ok(Map.of(
                "nodes",       clusterProperties.nodes().stream()
                                   .map(n -> Map.of(
                                           "id",          n.id(),
                                           "host",        n.host(),
                                           "grpcPort",    n.grpcPort(),
                                           "httpPort",    n.httpPort(),
                                           "blacklisted", router.isBlacklisted(n.id())))
                                   .toList(),
                "activeConnections", eventBus.activeConnections()
        ));
    }

    // ─── Ring snapshot ────────────────────────────────────────────────────────

    /**
     * Returns the current consistent-hash ring as a JSON array.
     * Each element: {@code {"position": "hex…", "nodeId": "node-1"}}.
     * Useful for the UI ring visualizer and debugging key placement.
     */
    @GetMapping("/ring")
    public ResponseEntity<java.util.List<java.util.Map<String, String>>> ring() {
        return ResponseEntity.ok(router.ringSnapshot());
    }

    // ─── Kill / Restart ────────────────────────────────────────────────────────

    /**
     * Simulated partition: the coordinator stops routing to this node.
     * The node process itself is unaffected — this simulates a network partition.
     */
    @PostMapping("/nodes/{nodeId}/kill")
    public ResponseEntity<Map<String, String>> killNode(@PathVariable String nodeId) {
        router.blacklist(nodeId);
        eventBus.publish(ClusterEvent.nodeStatus(nodeId, "KILLED", "UNKNOWN", 0L));
        return ResponseEntity.ok(Map.of("status", "KILLED", "nodeId", nodeId));
    }

    /**
     * Lifts the simulated partition: coordinator resumes routing to this node.
     */
    @PostMapping("/nodes/{nodeId}/restart")
    public ResponseEntity<Map<String, String>> restartNode(@PathVariable String nodeId) {
        router.unblacklist(nodeId);
        eventBus.publish(ClusterEvent.nodeStatus(nodeId, "UP", "FOLLOWER", 0L));
        return ResponseEntity.ok(Map.of("status", "UP", "nodeId", nodeId));
    }
}
