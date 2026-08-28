package com.kvstore.coordinator.monitoring;

import com.kvstore.coordinator.config.ClusterProperties;
import com.kvstore.coordinator.config.NodeInfo;
import com.kvstore.coordinator.routing.ConsistentHashRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls each node's {@code GET /api/v1/storage/state} endpoint every 2 seconds
 * and publishes {@link ClusterEvent#memtable} events to the dashboard.
 *
 * <p>Also detects nodes that fail to respond (marked as DOWN) and re-emits
 * {@link ClusterEvent#nodeStatus} with {@code status=DOWN}.
 *
 * <p>Requires {@code @EnableScheduling} on the application class.
 */
@Component
public class NodeStatePoller {

    private static final Logger log = LoggerFactory.getLogger(NodeStatePoller.class);

    private final ClusterProperties   clusterProperties;
    private final ClusterEventBus     eventBus;
    private final ConsistentHashRouter router;
    private final RestTemplate        http = new RestTemplate();

    /** Last known SSTable count per node — used to detect flushes. */
    private final ConcurrentHashMap<String, Integer> lastSstCount = new ConcurrentHashMap<>();

    public NodeStatePoller(ClusterProperties clusterProperties,
                           ClusterEventBus eventBus,
                           ConsistentHashRouter router) {
        this.clusterProperties = clusterProperties;
        this.eventBus          = eventBus;
        this.router            = router;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        for (NodeInfo node : clusterProperties.nodeInfoList()) {
            try {
                String url = node.httpBaseUrl() + "/api/v1/storage/state";
                @SuppressWarnings("unchecked")
                Map<String, Object> state = http.getForObject(url, Map.class);
                if (state == null) continue;

                int  fill        = (Integer) state.getOrDefault("memtableFillPercent", 0);
                long walBytes    = ((Number) state.getOrDefault("walSizeBytes", 0L)).longValue();
                int  sstCount    = (Integer) state.getOrDefault("sstableCount", 0);
                String role      = (String) state.getOrDefault("role", "FOLLOWER");
                long raftTerm    = ((Number) state.getOrDefault("raftTerm", 0L)).longValue();

                // Publish memtable state
                eventBus.publish(ClusterEvent.memtable(node.id(), fill, walBytes, sstCount));

                // Detect SSTable flush (sstCount increased)
                int prev = lastSstCount.getOrDefault(node.id(), 0);
                if (sstCount > prev) {
                    eventBus.publish(ClusterEvent.sstFlush(node.id(), sstCount));
                }
                lastSstCount.put(node.id(), sstCount);

                // Emit UP status only if node was previously unreachable
                if (router.isBlacklisted(node.id())) {
                    // Don't override kill status with UP from polling
                } else {
                    eventBus.publish(ClusterEvent.nodeStatus(node.id(), "UP", role, raftTerm));
                }

            } catch (Exception e) {
                log.warn("Node '{}' unreachable at {}: {}", node.id(), node.httpBaseUrl(), e.getMessage());
                if (!router.isBlacklisted(node.id())) {
                    eventBus.publish(ClusterEvent.nodeStatus(node.id(), "DOWN", "UNKNOWN", 0L));
                }
            }
        }
    }
}
