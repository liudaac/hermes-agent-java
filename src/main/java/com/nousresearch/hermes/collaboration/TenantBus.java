package com.nousresearch.hermes.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Peer-to-peer message bus for agent collaboration.
 * 
 * <p>Routes messages between tenants/agents, enabling:
 * <ul>
 *   <li>Peer-to-peer requests and responses</li>
 *   <li>Negotiation proposals</li>
 *   <li>Broadcast notifications</li>
 *   <li>Escalation chains</li>
 * </ul>
 * 
 * <p>Each agent registers a message handler. The bus delivers messages
 * asynchronously and supports reply tracking with timeouts.</p>
 */
public class TenantBus {
    private static final Logger logger = LoggerFactory.getLogger(TenantBus.class);
    
    private static volatile TenantBus instance;
    
    // Message handlers keyed by tenant/agent ID
    private final ConcurrentHashMap<String, Consumer<AgentMessage>> handlers = new ConcurrentHashMap<>();
    
    // Pending reply futures keyed by message ID
    private final ConcurrentHashMap<String, CompletableFuture<AgentMessage>> pendingReplies = new ConcurrentHashMap<>();
    
    // Message queue for async delivery
    private final BlockingQueue<AgentMessage> deliveryQueue = new LinkedBlockingQueue<>();
    private final ExecutorService deliveryExecutor = Executors.newCachedThreadPool();
    
    // Message history for audit/logging
    private final ConcurrentLinkedDeque<AgentMessage> messageHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 1000;
    
    private volatile boolean running = false;
    
    private TenantBus() {}
    
    public static synchronized TenantBus getInstance() {
        if (instance == null) {
            instance = new TenantBus();
        }
        return instance;
    }
    
    /**
     * Start the message delivery loop.
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        
        Thread deliveryThread = new Thread(() -> {
            while (running) {
                try {
                    AgentMessage msg = deliveryQueue.poll(1, TimeUnit.SECONDS);
                    if (msg != null) {
                        deliver(msg);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "tenant-bus-delivery");
        deliveryThread.setDaemon(true);
        deliveryThread.start();
        
        logger.info("TenantBus started");
    }
    
    /**
     * Stop the message bus.
     */
    public synchronized void stop() {
        running = false;
        deliveryExecutor.shutdown();
        for (CompletableFuture<AgentMessage> future : pendingReplies.values()) {
            future.completeExceptionally(new RuntimeException("Bus stopped"));
        }
        pendingReplies.clear();
        logger.info("TenantBus stopped");
    }
    
    /**
     * Register an agent to receive messages.
     */
    public void register(String agentId, Consumer<AgentMessage> handler) {
        handlers.put(agentId, handler);
        logger.info("Agent '{}' registered on bus", agentId);
    }
    
    /**
     * Unregister an agent.
     */
    public void unregister(String agentId) {
        handlers.remove(agentId);
        // Clean up pending replies for this agent
        pendingReplies.values().removeIf(f -> f.isDone());
        logger.info("Agent '{}' unregistered from bus", agentId);
    }
    
    /**
     * Send a message asynchronously. Returns immediately.
     */
    public void send(AgentMessage message) {
        message.setStatus(AgentMessage.Status.DELIVERED);
        addToHistory(message);
        
        if (message.getType() == AgentMessage.Type.BROADCAST) {
            // Broadcast: deliver to all registered agents except sender
            for (Map.Entry<String, Consumer<AgentMessage>> entry : handlers.entrySet()) {
                if (!entry.getKey().equals(message.getSenderId())) {
                    deliveryQueue.offer(message);
                }
            }
        } else if (message.getType() == AgentMessage.Type.ESCALATE) {
            // Escalation: deliver to all agents with role LEAD or SENIOR
            // In current implementation, broadcast to all
            for (Map.Entry<String, Consumer<AgentMessage>> entry : handlers.entrySet()) {
                if (!entry.getKey().equals(message.getSenderId())) {
                    deliveryQueue.offer(message);
                }
            }
        } else {
            // Point-to-point
            deliveryQueue.offer(message);
        }
        
        logger.debug("Queued: {}", message);
    }
    
    /**
     * Send a message and wait for a reply (blocking with timeout).
     */
    public AgentMessage sendAndWait(AgentMessage message, long timeoutMs) 
            throws TimeoutException {
        CompletableFuture<AgentMessage> future = new CompletableFuture<>();
        pendingReplies.put(message.getMessageId(), future);
        
        send(message);
        
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for reply", e);
        } catch (java.util.concurrent.TimeoutException e) {
            pendingReplies.remove(message.getMessageId());
            throw new TimeoutException("No reply from " + message.getReceiverId() 
                + " within " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            throw new RuntimeException("Reply failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Reply to a message.
     */
    public void reply(AgentMessage original, AgentMessage reply) {
        CompletableFuture<AgentMessage> future = pendingReplies.remove(original.getMessageId());
        if (future != null) {
            future.complete(reply);
        } else {
            // No pending wait — deliver as regular message
            send(reply);
        }
    }
    
    /**
     * Check if an agent is registered.
     */
    public boolean isRegistered(String agentId) {
        return handlers.containsKey(agentId);
    }
    
    /**
     * List all registered agents.
     */
    public Set<String> listAgents() {
        return Collections.unmodifiableSet(new HashSet<>(handlers.keySet()));
    }
    
    /**
     * Get recent message history for auditing.
     */
    public List<AgentMessage> getHistory(int limit) {
        List<AgentMessage> list = new ArrayList<>(messageHistory);
        return list.subList(Math.max(0, list.size() - limit), list.size());
    }
    
    /**
     * Get pending reply count (for health monitoring).
     */
    public int getPendingReplyCount() {
        return pendingReplies.size();
    }
    
    /**
     * Get queue depth (for health monitoring).
     */
    public int getQueueDepth() {
        return deliveryQueue.size();
    }
    
    // ---- Private ----
    
    private void deliver(AgentMessage msg) {
        Consumer<AgentMessage> handler = handlers.get(msg.getReceiverId());
        if (handler == null) {
            logger.warn("No handler for receiver '{}', message: {}", 
                msg.getReceiverId(), msg.getMessageId());
            msg.setStatus(AgentMessage.Status.FAILED);
            msg.setResultText("Receiver not found: " + msg.getReceiverId());
            return;
        }
        
        try {
            handler.accept(msg);
            msg.setStatus(AgentMessage.Status.PROCESSED);
        } catch (Exception e) {
            logger.error("Handler for '{}' failed: {}", msg.getReceiverId(), e.getMessage());
            msg.setStatus(AgentMessage.Status.FAILED);
            msg.setResultText("Handler error: " + e.getMessage());
        }
    }
    
    private void addToHistory(AgentMessage msg) {
        messageHistory.addLast(msg);
        while (messageHistory.size() > MAX_HISTORY) {
            messageHistory.pollFirst();
        }
    }
    
    /**
     * Custom timeout exception for agent communication.
     */
    public static class TimeoutException extends Exception {
        public TimeoutException(String message) {
            super(message);
        }
    }
}
