package com.kvstore.node.health;

import com.kvstore.engine.StorageEngine;
import com.kvstore.node.config.NodeProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator health indicator for this node.
 *
 * <p>Exposed at {@code GET /actuator/health}.
 * Reports live key count and node ID in the health detail so the
 * coordinator can introspect node state without a gRPC call.
 */
@Component
public class NodeHealthIndicator implements HealthIndicator {

    private final StorageEngine  storageEngine;
    private final NodeProperties nodeProperties;

    public NodeHealthIndicator(StorageEngine storageEngine, NodeProperties nodeProperties) {
        this.storageEngine  = storageEngine;
        this.nodeProperties = nodeProperties;
    }

    @Override
    public Health health() {
        try {
            long liveKeys = storageEngine.size();
            return Health.up()
                    .withDetail("nodeId",   nodeProperties.id())
                    .withDetail("liveKeys", liveKeys)
                    .withDetail("engine",   "LsmStorageEngine")
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("nodeId", nodeProperties.id())
                    .build();
        }
    }
}
