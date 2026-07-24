package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.collaboration.AgentMessage;
import com.nousresearch.hermes.collaboration.TenantBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Emits {@link AgentEvent}s to subscribers.
 *
 * <p>Three delivery paths:
 * <ol>
 *   <li><b>In-process subscribers</b> - {@link #subscribe(Subscriber)} for
 *       same-JVM listeners (Jarvis orb, metrics collector, etc.)</li>
 *   <li><b>TenantBus</b> - broadcast to the tenant's pub/sub bus for
 *       cross-session awareness</li>
 *   <li><b>SSE stream</b> - {@link #drain()} returns queued events for
 *       the SSE endpoint to flush to the browser</li>
 * </ol></p>
 *
 * <p>This replaces scattered {@code logger.info()} calls with a single
 * structured event channel.</p>
 */
public class EventEmitter {
    private static final Logger logger = LoggerFactory.getLogger(EventEmitter.class);

    private final String tenantId;
    private final String sessionId;
    private final String agentId;
    private final TenantBus tenantBus;

    /** In-process subscribers. */
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** Buffered events for SSE drain (bounded to prevent memory leak). */
    private final java.util.concurrent.ConcurrentLinkedQueue<AgentEvent> sseQueue =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int SSE_QUEUE_MAX = 256;

    @FunctionalInterface
    public interface Subscriber {
        void onEvent(AgentEvent event);
    }

    public EventEmitter(String tenantId, String sessionId, String agentId, TenantBus bus) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.tenantBus = bus;
    }

    /** Register an in-process subscriber. */
    public void subscribe(Subscriber sub) {
        subscribers.add(sub);
    }

    /** Unregister a subscriber. */
    public void unsubscribe(Subscriber sub) {
        subscribers.remove(sub);
    }

    /**
     * Emit an event to all delivery paths.
     * @param type  event type (see {@link AgentEvent} constants)
     * @param data  event payload
     */
    public void emit(String type, Map<String, Object> data) {
        var event = AgentEvent.of(type, tenantId, sessionId, agentId, data);

        // 1. In-process subscribers
        for (var sub : subscribers) {
            try {
                sub.onEvent(event);
            } catch (Exception e) {
                logger.warn("Subscriber failed for event {}: {}", type, e.getMessage());
            }
        }

        // 2. SSE queue
        sseQueue.add(event);
        while (sseQueue.size() > SSE_QUEUE_MAX) {
            sseQueue.poll();
        }

        // 3. TenantBus (best-effort, non-blocking)
        if (tenantBus != null) {
            try {
                var msg = AgentMessage.builder(agentId, "*", AgentMessage.Type.BROADCAST)
                    .action("AGENT_EVENT")
                    .payload(Map.of(
                        "type", type,
                        "data", data,
                        "sessionId", sessionId,
                        "timestamp", event.timestamp()
                    ))
                    .build();
                tenantBus.send(msg);
            } catch (Exception e) {
                logger.debug("TenantBus broadcast failed for {}: {}", type, e.getMessage());
            }
        }
    }

    /** Convenience: emit with factory. */
    public void emit(AgentEvent event) {
        for (var sub : subscribers) {
            try { sub.onEvent(event); } catch (Exception e) {
                logger.warn("Subscriber failed: {}", e.getMessage());
            }
        }
        sseQueue.add(event);
        while (sseQueue.size() > SSE_QUEUE_MAX) sseQueue.poll();
    }

    /** Drain pending events for SSE delivery. */
    public List<AgentEvent> drain() {
        var list = new java.util.ArrayList<AgentEvent>();
        AgentEvent e;
        while ((e = sseQueue.poll()) != null) {
            list.add(e);
        }
        return list;
    }

    /** Check if there are pending events. */
    public boolean hasPending() {
        return !sseQueue.isEmpty();
    }
}
