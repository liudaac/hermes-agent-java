package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.collaboration.AgentMessage;
import com.nousresearch.hermes.collaboration.TenantBus;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.harness.ForkMode;
import com.nousresearch.hermes.harness.ModelProvider;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolDefinition;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Sub-agent for parallel task delegation.
 * Each sub-agent runs independently with its own context and budget.
 *
 * <p>Supports optional tool whitelist and system-prompt override for
 * specialized forks (background review, curator, etc.).</p>
 */
public class SubAgent implements Callable<SubAgentResult> {
    private static final Logger logger = LoggerFactory.getLogger(SubAgent.class);
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    
    /** Default tool set for general-purpose sub-agents. */
    private static final Set<String> DEFAULT_TOOLS = Set.of(
        "web_search", "web_extract", "read_file", "write_file",
        "execute_command", "search_files"
    );
    
    private final String id;
    private final String task;
    private final String context;
    private final HermesConfig config;
    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final IterationBudget budget;
    private final List<ModelMessage> conversationHistory;
    
    // Forked history (pre-seeded from parent, before system prompt)
    private List<ModelMessage> forkedHistory = null;

    // Optional overrides for specialized forks
    private Set<String> toolWhitelist = null;
    private String systemPromptOverride = null;
    private Integer maxIterationsOverride = null;
    
    private volatile boolean running;
    private SubAgentResult result;
    private TenantBus bus;  // opt-in tenant bus for inter-agent comms
    private String busAgentId;
    
    /**
     * Original constructor - creates its own ModelClient.
     * Kept for backward compatibility.
     */
    public SubAgent(String task, String context, HermesConfig config) {
        this(task, context, config, new ModelClient(config.getModelConfig()));
    }

    /**
     * New constructor - accepts an external ModelProvider.
     * Use this when spawning from AgentContext to share the parent's
     * model connection (connection pool reuse, no duplicate HTTP clients).
     */
    public SubAgent(String task, String context, HermesConfig config, ModelProvider modelProvider) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.task = task;
        this.context = context;
        this.config = config;
        this.modelProvider = modelProvider;
        this.toolRegistry = ToolRegistry.getInstance();
        this.budget = new IterationBudget(config.getMaxTurns() / 2);
        this.conversationHistory = new ArrayList<>();
        this.running = false;
    }
    
    /** Connect to tenant bus for inter-agent communication */
    public SubAgent withBus(TenantBus tenantBus) {
        this.bus = tenantBus;
        this.busAgentId = id;
        if (bus != null) {
            bus.register(busAgentId, msg -> {
                logger.debug("[SubAgent {}] received message: {} action={}", 
                    id, msg.getType(), msg.getAction());
                
                // Handle REQUEST messages from other agents
                if (msg.getType() == AgentMessage.Type.REQUEST) {
                    handleBusRequest(msg);
                } else if (msg.getType() == AgentMessage.Type.NOTIFY) {
                    // Store notifications as context
                    String notification = "[" + msg.getSenderId() + "]: " + 
                        msg.getAction() + " " + msg.getPayload();
                    synchronized (conversationHistory) {
                        conversationHistory.add(ModelMessage.system(
                            "Notification from peer: " + notification));
                    }
                }
            });
            bus.start();
        }
        return this;
    }

    /**
     * Handle a REQUEST message from another agent on the bus.
     * Sends a reply with the best available information.
     */
    private void handleBusRequest(AgentMessage request) {
        if (bus == null) return;
        
        String action = request.getAction();
        Map<String, Object> payload = request.getPayload();
        
        String replyText;
        if ("status".equals(action)) {
            replyText = running ? "running (iteration " + budget.getUsed() + ")" : "idle";
        } else if ("partial_result".equals(action)) {
            // Return current output so far
            StringBuilder sb = new StringBuilder();
            for (ModelMessage m : conversationHistory) {
                if ("assistant".equals(m.getRole()) && m.getContent() != null) {
                    sb.append(m.getContent()).append("\n");
                }
            }
            replyText = sb.length() > 0 ? sb.toString() : "No output yet";
        } else if ("query".equals(action)) {
            // Answer a question from another agent using conversation context
            String question = payload != null ? String.valueOf(payload.get("question")) : "";
            replyText = answerFromContext(question);
        } else {
            replyText = "Unknown action: " + action;
        }
        
        AgentMessage reply = AgentMessage.builder(busAgentId, request.getSenderId(), 
                AgentMessage.Type.RESPONSE)
            .action(action)
            .payload(Map.of("result", replyText))
            .replyTo(request.getMessageId())
            .build();
        bus.reply(request, reply);
    }

    /**
     * Best-effort answer to a peer query from conversation history.
     */
    private String answerFromContext(String question) {
        // Simple: return last assistant message that mentions keywords from the question
        String[] keywords = question.toLowerCase().split("\\s+");
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            ModelMessage m = conversationHistory.get(i);
            if ("assistant".equals(m.getRole()) && m.getContent() != null) {
                String content = m.getContent().toLowerCase();
                for (String kw : keywords) {
                    if (kw.length() > 3 && content.contains(kw)) {
                        return m.getContent();
                    }
                }
            }
        }
        return "No relevant information found in current context";
    }

    /**
     * Send a message to another agent on the bus.
     */
    public AgentMessage askPeer(String peerId, String action, Map<String, Object> payload, 
                                 long timeoutMs) {
        if (bus == null) return null;
        
        AgentMessage msg = AgentMessage.builder(busAgentId, peerId, AgentMessage.Type.REQUEST)
            .action(action)
            .payload(payload != null ? payload : Map.of())
            .timeoutMs(timeoutMs)
            .build();
        
        try {
            return bus.sendAndWait(msg, timeoutMs);
        } catch (TenantBus.TimeoutException e) {
            logger.warn("[SubAgent {}] Peer {} did not respond in {}ms", id, peerId, timeoutMs);
            return null;
        }
    }

    /**
     * Notify all peers on the bus.
     */
    public void notifyPeers(String action, Map<String, Object> payload) {
        if (bus == null) return;
        
        AgentMessage msg = AgentMessage.builder(busAgentId, "all", AgentMessage.Type.BROADCAST)
            .action(action)
            .payload(payload != null ? payload : Map.of())
            .build();
        bus.send(msg);
    }

    /**
     * Fork from a parent agent's conversation history.
     *
     * <p>After this call, the sub-agent's conversation will start with
     * the forked history (optionally compressed), followed by the
     * sub-agent's own system prompt and task.</p>
     *
     * @param parentHistory  the parent agent's conversation history
     * @param mode           how much history to fork
     */
    public SubAgent forkFrom(List<ModelMessage> parentHistory, ForkMode mode) {
        if (parentHistory == null || parentHistory.isEmpty()) {
            return this; // nothing to fork
        }

        switch (mode) {
            case FULL -> {
                // Deep copy: new ModelMessage objects
                this.forkedHistory = new ArrayList<>(parentHistory.size());
                for (ModelMessage m : parentHistory) {
                    this.forkedHistory.add(copyMessage(m));
                }
            }
            case COMPRESSED -> {
                // Copy, then run ContextManager compression on the copy
                List<ModelMessage> copy = new ArrayList<>(parentHistory.size());
                for (ModelMessage m : parentHistory) {
                    copy.add(copyMessage(m));
                }
                var cm = new com.nousresearch.hermes.harness.ContextManager();
                cm.enforce(copy, null);
                this.forkedHistory = copy;
            }
            case CLEAN -> {
                // No fork, just use context string (existing behavior)
                this.forkedHistory = null;
            }
        }
        return this;
    }

    private static ModelMessage copyMessage(ModelMessage src) {
        String role = src.getRole();
        if ("system".equals(role)) return ModelMessage.system(src.getContent());
        if ("user".equals(role)) return ModelMessage.user(src.getContent());
        if ("assistant".equals(role)) {
            var msg = ModelMessage.assistant(src.getContent());
            if (src.getToolCalls() != null) msg.setToolCalls(src.getToolCalls());
            return msg;
        }
        if ("tool".equals(role)) return ModelMessage.tool(src.getContent(), src.getToolCallId());
        return ModelMessage.system(src.getContent()); // fallback
    }

    /** Restrict the sub-agent to a specific set of tools (whitelist). */
    public SubAgent withToolWhitelist(Set<String> tools) {
        this.toolWhitelist = tools != null ? new java.util.HashSet<>(tools) : null;
        return this;
    }

    /** Override the system prompt for specialized forks (review, curator, etc.). */
    public SubAgent withSystemPrompt(String prompt) {
        this.systemPromptOverride = prompt;
        return this;
    }

    /** Override max iterations for this sub-agent. */
    public SubAgent withMaxIterations(int max) {
        this.maxIterationsOverride = max;
        return this;
    }

    @Override
    public SubAgentResult call() throws Exception {
        running = true;
        long startTime = System.currentTimeMillis();
        
        try {
            logger.info("[SubAgent {}] Starting task: {}", id, task.substring(0, Math.min(50, task.length())));
            
            // Seed from forked history if available
            if (forkedHistory != null && !forkedHistory.isEmpty()) {
                // Remove the parent's system prompt (first message) - we'll add our own
                int start = 0;
                if ("system".equals(forkedHistory.get(0).getRole())) {
                    start = 1;
                }
                for (int i = start; i < forkedHistory.size(); i++) {
                    conversationHistory.add(copyMessage(forkedHistory.get(i)));
                }
                logger.debug("[SubAgent {}] Forked {} messages from parent", id, forkedHistory.size() - start);
            }
            
            // Build system prompt for sub-agent
            String systemPrompt = buildSystemPrompt();
            conversationHistory.add(0, ModelMessage.system(systemPrompt));
            
            // Add context if provided
            if (context != null && !context.isEmpty()) {
                conversationHistory.add(ModelMessage.system("Context:\n" + context));
            }
            
            // Add task
            conversationHistory.add(ModelMessage.user(task));
            
            // Run conversation loop
            StringBuilder output = new StringBuilder();
            boolean completed = false;
            
            while (running && budget.hasRemaining() && !completed) {
                if (!budget.consume()) {
                    break;
                }
                
                // Call model - get tool definitions from registry
                List<ToolDefinition> toolDefs = buildToolDefinitions();
                var response = modelProvider.chat(
                    conversationHistory,
                    toolDefs.isEmpty() ? null : toolDefs,
                    false,
                    null
                );
                
                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    break;
                }
                
                conversationHistory.add(assistantMessage);
                
                // Handle tool calls
                if (response.hasToolCalls()) {
                    for (var toolCall : assistantMessage.getToolCalls()) {
                        String toolResult = executeToolCall(toolCall);
                        conversationHistory.add(ModelMessage.tool(toolResult, toolCall.getId()));
                    }
                } else {
                    // No tool calls, task complete
                    String content = assistantMessage.getContent();
                    if (content != null) {
                        output.append(content);
                    }
                    completed = true;
                }
                
                if ("stop".equals(response.getFinishReason())) {
                    completed = true;
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            // Extract insights from sub-agent's work for shared memory
            java.util.List<String> insights = new java.util.ArrayList<>();
            java.util.List<String> memories = new java.util.ArrayList<>();
            try {
                com.nousresearch.hermes.learning.InsightExtractor extractor = 
                    new com.nousresearch.hermes.learning.InsightExtractor();
                var entry = new com.nousresearch.hermes.trajectory.TrajectoryEntry();
                entry.setConversations(new java.util.ArrayList<>(conversationHistory));
                entry.setCompleted(completed);
                insights = extractor.extract(entry);
                for (String insight : insights) {
                    if (insight.startsWith("Tools used:") || insight.startsWith("User preference:") 
                        || insight.startsWith("Memory hint:")) {
                        memories.add(insight);
                    }
                }
                logger.info("[SubAgent {}] Extracted {} insights, {} memories", id, insights.size(), memories.size());
            } catch (Exception ex) {
                logger.debug("[SubAgent {}] Insight extraction skipped: {}", id, ex.getMessage());
            }
            
            result = new SubAgentResult(id, task, output.toString(), completed, 
                completed, null, budget.getUsed(), duration, insights, memories);
            
            logger.info("[SubAgent {}] Completed in {}ms ({} iterations)", 
                id, duration, budget.getUsed());
            
            return result;
            
        } catch (Exception e) {
            logger.error("[SubAgent {}] Failed: {}", id, e.getMessage(), e);
            
            result = new SubAgentResult(id, task, "Error: " + e.getMessage(), 
                false, false, e.getMessage(), budget.getUsed(), 
                System.currentTimeMillis() - startTime);
            
            return result;
        } finally {
            running = false;
        }
    }
    
    /**
     * Spawn multiple sub-agents in parallel.
     */
    public static List<SubAgentResult> spawnParallel(List<String> tasks, String context, 
                                                      HermesConfig config, long timeoutMs) {
        List<Future<SubAgentResult>> futures = new ArrayList<>();
        List<SubAgentResult> results = new ArrayList<>();
        
        // Submit all tasks
        for (String task : tasks) {
            SubAgent agent = new SubAgent(task, context, config);
            futures.add(executor.submit(agent));
        }
        
        // Collect results with timeout
        for (Future<SubAgentResult> future : futures) {
            try {
                SubAgentResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                results.add(result);
            } catch (TimeoutException e) {
                future.cancel(true);
                SubAgentResult timeoutResult = new SubAgentResult("timeout", "", "", 
                    false, false, "Timeout after " + timeoutMs + "ms", 0, timeoutMs);
                results.add(timeoutResult);
            } catch (Exception e) {
                SubAgentResult errorResult = new SubAgentResult("error", "", "", 
                    false, false, e.getMessage(), 0, 0);
                results.add(errorResult);
            }
        }
        
        return results;
    }
    
    /**
     * Stop the sub-agent.
     */
    public void stop() {
        running = false;
    }
    
    public String getId() {
        return id;
    }
    
    private String buildSystemPrompt() {
        if (systemPromptOverride != null && !systemPromptOverride.isBlank()) {
            return systemPromptOverride;
        }
        return "You are a sub-agent working on a specific task. " +
               "Focus on completing the task efficiently. " +
               "Use tools when needed. " +
               "Return your final result when done.";
    }
    
    private List<ToolDefinition> buildToolDefinitions() {
        Set<String> tools = toolWhitelist != null ? toolWhitelist : DEFAULT_TOOLS;
        return toolRegistry.getToolDefinitions(tools);
    }
    
    private String executeToolCall(com.nousresearch.hermes.model.ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(arguments, Map.class);
            return toolRegistry.dispatch(toolName, args);
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed to execute tool: " + e.getMessage());
        }
    }
}