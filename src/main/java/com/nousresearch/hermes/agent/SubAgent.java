package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Sub-agent for parallel task delegation.
 * Each sub-agent runs independently with its own context and budget.
 */
public class SubAgent implements Callable<SubAgentResult> {
    private static final Logger logger = LoggerFactory.getLogger(SubAgent.class);
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    
    private final String id;
    private final String task;
    private final String context;
    private final HermesConfig config;
    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final IterationBudget budget;
    private final List<ModelMessage> conversationHistory;
    
    private volatile boolean running;
    private SubAgentResult result;
    
    public SubAgent(String task, String context, HermesConfig config) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.task = task;
        this.context = context;
        this.config = config;
        this.modelClient = new ModelClient(config);
        this.toolRegistry = ToolRegistry.getInstance();
        this.budget = new IterationBudget(config.getMaxTurns() / 2); // Sub-agents get half budget
        this.conversationHistory = new ArrayList<>();
        this.running = false;
    }
    
    @Override
    public SubAgentResult call() throws Exception {
        running = true;
        long startTime = System.currentTimeMillis();
        
        try {
            logger.info("[SubAgent {}] Starting task: {}", id, task.substring(0, Math.min(50, task.length())));
            
            // Build system prompt for sub-agent
            String systemPrompt = buildSystemPrompt();
            conversationHistory.add(ModelMessage.system(systemPrompt));
            
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
                
                // Call model
                ModelClient.ChatCompletionResponse response = modelClient.chatCompletion(
                    conversationHistory,
                    buildToolDefinitions(),
                    false
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
            
            result = new SubAgentResult(id, task, output.toString(), completed, 
                completed, null, budget.getUsed(), duration);
            
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
        return "You are a sub-agent working on a specific task. " +
               "Focus on completing the task efficiently. " +
               "Use tools when needed. " +
               "Return your final result when done.";
    }
    
    private List<Map<String, Object>> buildToolDefinitions() {
        // Get a subset of tools for sub-agents
        var toolNames = List.of("web_search", "web_extract", "read_file", "write_file", 
                                "execute_command", "search_files");
        return toolRegistry.getDefinitions(java.util.Set.copyOf(toolNames), true);
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