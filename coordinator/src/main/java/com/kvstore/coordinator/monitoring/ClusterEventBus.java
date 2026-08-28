package com.kvstore.coordinator.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory publish-subscribe bus that fans out {@link ClusterEvent}s to all
 * connected dashboard SSE clients.
 *
 * <p>Uses {@link CopyOnWriteArrayList} so that {@link #publish} can iterate
 * without locking while {@link #addEmitter} / completion callbacks concurrently
 * remove emitters.
 */
@Component
public class ClusterEventBus {

    private static final Logger log = LoggerFactory.getLogger(ClusterEventBus.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public void addEmitter(SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()   -> emitters.remove(emitter));
        emitter.onError(e      -> emitters.remove(emitter));
        emitters.add(emitter);
        log.debug("SSE client connected. Total: {}", emitters.size());
    }

    /**
     * Publishes an event to all connected dashboard clients.
     * Removes any emitter that fails to send (client disconnected).
     */
    public void publish(ClusterEvent event) {
        if (emitters.isEmpty()) return;

        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("Failed to serialize ClusterEvent: {}", e.getMessage());
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("cluster-event")
                        .data(json));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
        if (!dead.isEmpty()) {
            log.debug("Removed {} disconnected SSE emitter(s). Active: {}", dead.size(), emitters.size());
        }
    }

    public int activeConnections() {
        return emitters.size();
    }
}
