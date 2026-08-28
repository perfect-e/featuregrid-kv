package com.kvstore.coordinator;

import com.kvstore.coordinator.client.NodeGrpcClient;
import com.kvstore.coordinator.config.ClusterProperties;
import com.kvstore.coordinator.routing.ConsistentHashRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the KV coordinator service.
 *
 * <p>The coordinator is a stateless routing layer — it does not store data.
 * Its responsibilities:
 * <ol>
 *   <li>Expose a REST API for clients ({@link com.kvstore.coordinator.api.KvRestController}).</li>
 *   <li>Resolve which node owns a key using a consistent-hash ring (MD5, 150 VNodes/node).</li>
 *   <li>Forward the primary write via gRPC, then fan-out replicas to all other live nodes.</li>
 * </ol>
 */
@SpringBootApplication
@EnableConfigurationProperties(ClusterProperties.class)
@EnableScheduling
public class CoordinatorApplication {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CoordinatorApplication.class, args);
    }

    /**
     * Registers all cluster nodes with the gRPC client and the router at startup.
     * Uses a Spring {@code @Bean} method so initialization happens after all
     * properties are bound and before the HTTP server starts accepting requests.
     */
    @Bean
    public NodeGrpcClient nodeGrpcClient(ClusterProperties clusterProperties,
                                          ConsistentHashRouter router) {
        var nodes = clusterProperties.nodeInfoList();
        log.info("Coordinator starting with {} node(s):", nodes.size());
        nodes.forEach(n -> log.info("  → {} at {}:{} (gRPC)", n.id(), n.host(), n.grpcPort()));

        NodeGrpcClient client = new NodeGrpcClient();
        client.registerNodes(nodes);

        router.setNodes(nodes);
        log.info("ConsistentHashRouter ready: {} VNodes/node × {} nodes = {} ring slots",
                ConsistentHashRouter.VIRTUAL_NODES_PER_NODE, nodes.size(),
                ConsistentHashRouter.VIRTUAL_NODES_PER_NODE * nodes.size());
        return client;
    }
}
