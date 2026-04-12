package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;
import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.tools.ToolInitializer;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core AI Agent implementation.
 * Handles the conversation loop, tool calling, and response management.
 */
public class AIAgent {
    private static final Logger logger = LoggerFactory.getLogger(AIAgent.class);
    
    private final HermesConfig config;
    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final IterationBudget iterationBudget;
    private final MemoryManager memoryManager;
    private final List<ModelMessage> conversationHistory;
    private final AtomicBoolean interrupted;
    
    // Tool definitions cache
    private List<Map<String, Object>> toolDefinitions;
    
    public AIAgent(HermesConfig config) {
        this.config = config;
        this.modelClient = new ModelClient(config);
        this.toolRegistry = ToolRegistry.getInstance();
        this.iterationBudget = new IterationBudget(config.getMaxTurns());
        this.memoryManager = new MemoryManager();
        this.conversationHistory = new ArrayList<>();
        this.interrupted = new AtomicBoolean(false);
        
        // Initialize tools
        initializeTools();
    }
    
    /**
     * Initialize available tools.
     */
    private void initializeTools() {
        // Register all built-in tools
        ToolInitializer.initialize();
        
        // Build tool definitions for the model
        toolDefinitions = buildToolDefinitions();
        logger.debug("Initialized with {} tools", toolDefinitions.size());
    }
    
    /**
     * Build tool definitions from registry.
     */
    private List<Map<String, Object>> buildToolDefinitions() {
        // Get enabled tools from config
        List<String> enabledTools = config.getEnabledTools();
        Set<String> toolNames = new HashSet<>();
        
        // Map toolsets to individual tools
        for (String toolset : enabledTools) {
            // In real implementation, this would get tools from the toolset
            // For now, add some default tools
            switch (toolset) {
                case "web_search":
                    toolNames.add("web_search");
                    toolNames.add("web_extract");
                    break;
                case "terminal":
                    toolNames.add("execute_command");
                    break;
                case "file_operations":
                    toolNames.add("read_file");
                    toolNames.add("write_file");
                    toolNames.add("search_files");
                    break;
                case "browser":
                    toolNames.add("browser_open");
                    toolNames.add("browser_snapshot");
                    break;
            }
        }
        
        return toolRegistry.getDefinitions(toolNames, false);
    }
    
    /**
     * Run interactive CLI mode.
     */
    public void runInteractive() {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║     Hermes Agent Java v" + Constants.VERSION + "      ║");
        System.out.println("║   Self-improving AI with tool calling  ║");
        System.out.println("╚════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Model: " + config.getCurrentModel());
        System.out.println("Type 'exit' or '/quit' to exit");
        System.out.println("Type '/help' for commands");
        System.out.println();
        
        // Add system message
        conversationHistory.add(ModelMessage.system(buildSystemPrompt()));
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        while (!interrupted.get()) {
            try {
                System.out.print("\nYou: ");
                String input = reader.readLine();
                
                if (input == null || input.trim().isEmpty()) {
                    continue;
                }
                
                // Handle commands
                if (input.startsWith("/")) {
                    if (handleCommand(input)) {
                        break;
                    }
                    continue;
                }
                
                // Process user message
                processUserMessage(input);
                
            } catch (IOException e) {
                logger.error("IO error: {}", e.getMessage());
                break;
            }
        }
        
        System.out.println("\nGoodbye!");
    }
    
    /**
     * Process a user message through the conversation loop.
     */
    private void processUserMessage(String userInput) {
        // Add user message to history
        conversationHistory.add(ModelMessage.user(userInput));
        
        // Conversation loop
        boolean continueLoop = true;
        while (continueLoop && !interrupted.get() && iterationBudget.hasRemaining()) {
            if (!iterationBudget.consume()) {
                System.out.println("\n[Reached maximum iterations]");
                break;
            }
            
            try {
                // Call the model
                ModelClient.ChatCompletionResponse response = modelClient.chatCompletion(
                    conversationHistory,
                    toolDefinitions,
                    false
                );
                
                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    System.out.println("\n[No response from model]");
                    break;
                }
                
                // Add assistant message to history
                conversationHistory.add(assistantMessage);
                
                // Check for tool calls
                if (response.hasToolCalls()) {
                    // Display assistant's reasoning
                    if (assistantMessage.getContent() != null && !assistantMessage.getContent().isEmpty()) {
                        System.out.println("\nAssistant: " + assistantMessage.getContent());
                    }
                    
                    // Execute tool calls
                    List<ToolCall> toolCalls = assistantMessage.getToolCalls();
                    for (ToolCall toolCall : toolCalls) {
                        String result = executeToolCall(toolCall);
                        
                        // Add tool result to conversation
                        conversationHistory.add(ModelMessage.tool(result, toolCall.getId()));
                    }
                    
                    // Continue loop for next iteration
                    continueLoop = true;
                } else {
                    // No tool calls, display response
                    String content = assistantMessage.getContent();
                    if (content != null && !content.isEmpty()) {
                        System.out.println("\nAssistant: " + content);
                    }
                    continueLoop = false;
                }
                
                // Check finish reason
                if ("stop".equals(response.getFinishReason())) {
                    continueLoop = false;
                }
                
            } catch (Exception e) {
                logger.error("Error in conversation loop: {}", e.getMessage());
                System.out.println("\n[Error: " + e.getMessage() + "]");
                break;
            }
        }
    }
    
    /**
     * Execute a tool call.
     */
    private String executeToolCall(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();
        
        System.out.println("  [Tool: " + toolName + "]");
        
        try {
            // Parse arguments
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(arguments, Map.class);
            
            // Dispatch to tool registry
            return toolRegistry.dispatch(toolName, args);
            
        } catch (Exception e) {
            logger.error("Failed to execute tool {}: {}", toolName, e.getMessage());
            return ToolRegistry.toolError("Failed to parse arguments: " + e.getMessage());
        }
    }
    
    /**
     * Build the system prompt.
     */
    private String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append(Constants.DEFAULT_AGENT_IDENTITY).append("\n\n");
        prompt.append(Constants.MEMORY_GUIDANCE).append("\n\n");
        prompt.append(Constants.TOOL_USE_ENFORCEMENT_GUIDANCE).append("\n\n");
        
        // Add memory context
        String memoryContext = memoryManager.buildMemoryContext();
        if (!memoryContext.isEmpty()) {
            prompt.append(memoryContext).append("\n");
        }
        
        // Add available tools info
        prompt.append("## Available Tools\n\n");
        for (Map<String, Object> tool : toolDefinitions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            if (function != null) {
                prompt.append("- ").append(function.get("name"));
                if (function.containsKey("description")) {
                    prompt.append(": ").append(function.get("description"));
                }
                prompt.append("\n");
            }
        }
        
        return prompt.toString();
    }
    
    /**
     * Handle slash commands.
     * @return true if should exit
     */
    private boolean handleCommand(String command) {
        String cmd = command.toLowerCase().trim();
        
        switch (cmd) {
            case "/quit":
            case "/exit":
            case "exit":
                return true;
            case "/help":
                printHelp();
                break;
            case "/clear":
                conversationHistory.clear();
                conversationHistory.add(ModelMessage.system(buildSystemPrompt()));
                System.out.println("[Conversation cleared]");
                break;
            case "/history":
                printHistory();
                break;
            case "/tools":
                printTools();
                break;
            default:
                System.out.println("Unknown command: " + command);
                System.out.println("Type /help for available commands");
        }
        
        return false;
    }
    
    private void printHelp() {
        System.out.println("\nAvailable commands:");
        System.out.println("  /help     - Show this help");
        System.out.println("  /clear    - Clear conversation history");
        System.out.println("  /history  - Show conversation history");
        System.out.println("  /tools    - List available tools");
        System.out.println("  /quit     - Exit");
        System.out.println();
    }
    
    private void printHistory() {
        System.out.println("\n--- Conversation History ---");
        for (int i = 0; i < conversationHistory.size(); i++) {
            ModelMessage msg = conversationHistory.get(i);
            String content = msg.getContent();
            if (content != null && !content.isEmpty()) {
                System.out.println(i + ". [" + msg.getRole() + "]: " + 
                    content.substring(0, Math.min(content.length(), 100)) + 
                    (content.length() > 100 ? "..." : ""));
            }
        }
        System.out.println("--- End of History ---\n");
    }
    
    private void printTools() {
        System.out.println("\n--- Available Tools ---");
        for (Map<String, Object> tool : toolDefinitions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            if (function != null) {
                System.out.println("  - " + function.get("name"));
            }
        }
        System.out.println("--- End of Tools ---\n");
    }
    
    /**
     * Interrupt the agent.
     */
    public void interrupt() {
        interrupted.set(true);
    }
}