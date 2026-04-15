package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;
import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.tools.ToolInitializer;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.trajectory.TrajectoryCollector;
import com.nousresearch.hermes.learning.KnowledgeExtractor;
import com.nousresearch.hermes.skills.SkillManager;
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
    private final com.nousresearch.hermes.gateway.SessionManager sessionManager;
    private final String sessionId;
    
    // Tool definitions cache
    private List<Map<String, Object>> toolDefinitions;
    
    // Auto-save settings
    private static final int AUTO_SAVE_INTERVAL = 5; // Save every 5 messages
    
    // Learning components
    private TrajectoryCollector trajectoryCollector;
    private KnowledgeExtractor knowledgeExtractor;
    private SkillManager skillManager;
    
    public AIAgent(HermesConfig config) {
        this.config = config;
        this.modelClient = new ModelClient(config);
        this.toolRegistry = ToolRegistry.getInstance();
        this.iterationBudget = new IterationBudget(config.getMaxTurns());
        this.memoryManager = new MemoryManager();
        this.conversationHistory = new ArrayList<>();
        this.interrupted = new AtomicBoolean(false);
        this.sessionManager = new com.nousresearch.hermes.gateway.SessionManager(
            Constants.getHermesHome().resolve("memory")
        );
        this.sessionId = "cli_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Initialize learning components
        initializeLearningComponents();
        
        // Initialize tools
        initializeTools();
        
        // Start trajectory tracking
        if (trajectoryCollector != null) {
            trajectoryCollector.startSession(this.sessionId, config.getCurrentModel());
        }
    }
    
    /**
     * Constructor with session ID for gateway mode.
     */
    public AIAgent(HermesConfig config, String sessionId) {
        this.config = config;
        this.modelClient = new ModelClient(config);
        this.toolRegistry = ToolRegistry.getInstance();
        this.iterationBudget = new IterationBudget(config.getMaxTurns());
        this.memoryManager = new MemoryManager();
        this.conversationHistory = new ArrayList<>();
        this.interrupted = new AtomicBoolean(false);
        this.sessionManager = new com.nousresearch.hermes.gateway.SessionManager(
            Constants.getHermesHome().resolve("memory")
        );
        this.sessionId = sessionId;
        
        // Initialize learning components
        initializeLearningComponents();
        
        // Initialize tools
        initializeTools();
        
        // Start trajectory tracking
        if (trajectoryCollector != null) {
            trajectoryCollector.startSession(this.sessionId, config.getCurrentModel());
        }
    }
    
    /**
     * Initialize learning components for the self-improvement loop.
     */
    private void initializeLearningComponents() {
        this.trajectoryCollector = new TrajectoryCollector();
        this.skillManager = new SkillManager();
        this.knowledgeExtractor = new KnowledgeExtractor(memoryManager, skillManager);
        logger.debug("Initialized learning components");
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
     * Process a message and return the response (for programmatic use).
     * @param message The user message
     * @return The assistant's response
     */
    public String processMessage(String message) {
        // Add user message to history
        conversationHistory.add(ModelMessage.user(message));
        
        // Auto-save session periodically
        autoSaveSession();
        
        StringBuilder responseBuilder = new StringBuilder();
        
        // Conversation loop
        boolean continueLoop = true;
        while (continueLoop && !interrupted.get() && iterationBudget.hasRemaining()) {
            if (!iterationBudget.consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
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
                    responseBuilder.append("\n[No response from model]");
                    break;
                }
                
                // Add assistant message to history
                conversationHistory.add(assistantMessage);
                
                // Auto-save after each assistant response
                autoSaveSession();
                
                // Check for tool calls
                if (response.hasToolCalls()) {
                    // Add assistant's reasoning to response
                    if (assistantMessage.getContent() != null && !assistantMessage.getContent().isEmpty()) {
                        if (responseBuilder.length() > 0) {
                            responseBuilder.append("\n\n");
                        }
                        responseBuilder.append(assistantMessage.getContent());
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
                    // No tool calls, get response content
                    String content = assistantMessage.getContent();
                    if (content != null && !content.isEmpty()) {
                        if (responseBuilder.length() > 0) {
                            responseBuilder.append("\n\n");
                        }
                        responseBuilder.append(content);
                    }
                    continueLoop = false;
                }
                
                // Check finish reason
                if ("stop".equals(response.getFinishReason())) {
                    continueLoop = false;
                }
                
            } catch (Exception e) {
                logger.error("Error in conversation loop: {}", e.getMessage());
                responseBuilder.append("\n[Error: ").append(e.getMessage()).append("]");
                break;
            }
        }
        
        // Final save after conversation
        persistSession();
        
        return responseBuilder.toString();
    }
    
    /**
     * Process a user message through the conversation loop (interactive CLI mode).
     */
    private void processUserMessage(String userInput) {
        // Add user message to history
        conversationHistory.add(ModelMessage.user(userInput));
        
        // Auto-save session periodically
        autoSaveSession();
        
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
                
                // Auto-save after each assistant response
                autoSaveSession();
                
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
                    } else if (content == null) {
                        System.out.println("\n[Assistant returned empty response]");
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
        
        // Final save after conversation
        persistSession();
    }
    
    /**
     * Auto-save session periodically.
     */
    private void autoSaveSession() {
        if (conversationHistory.size() % AUTO_SAVE_INTERVAL == 0) {
            persistSession();
        }
    }
    
    /**
     * Persist session to disk.
     */
    private void persistSession() {
        try {
            com.nousresearch.hermes.gateway.SessionManager.Session session = 
                sessionManager.getSession(sessionId);
            
            // Copy conversation history to session
            for (ModelMessage msg : conversationHistory) {
                if (msg.getRole() != null && msg.getContent() != null) {
                    session.addMessage(msg.getRole(), msg.getContent());
                }
            }
            
            sessionManager.saveSession(session);
            logger.debug("Session saved: {}", sessionId);
        } catch (Exception e) {
            logger.warn("Failed to save session: {}", e.getMessage());
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
        
        // Agent identity
        prompt.append(Constants.DEFAULT_AGENT_IDENTITY).append("\n\n");
        
        // Memory guidance
        prompt.append(Constants.MEMORY_GUIDANCE).append("\n\n");
        
        // Tool enforcement - CRITICAL for proper tool usage
        prompt.append(Constants.TOOL_USE_ENFORCEMENT_GUIDANCE).append("\n\n");
        
        // Execution discipline - CRITICAL for correct behavior
        prompt.append(Constants.EXECUTION_DISCIPLINE_GUIDANCE).append("\n\n");
        
        // Session search guidance
        prompt.append(Constants.SESSION_SEARCH_GUIDANCE).append("\n\n");
        
        // Skills guidance
        prompt.append(Constants.SKILLS_GUIDANCE).append("\n\n");
        
        // Platform hint (CLI mode)
        String platformHint = Constants.PLATFORM_HINTS.get("cli");
        if (platformHint != null) {
            prompt.append(platformHint).append("\n\n");
        }
        
        // Add memory context
        String memoryContext = memoryManager.getSystemPromptSnapshot();
        if (!memoryContext.isEmpty()) {
            prompt.append(memoryContext).append("\n\n");
        }
        
        // Add available tools info
        prompt.append("## Available Tools\n\n");
        for (Map<String, Object> tool : toolDefinitions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            if (function != null) {
                prompt.append("- ").append(function.get("name"));
                if (function.containsKey("description")) {
                    String desc = (String) function.get("description");
                    // Truncate long descriptions
                    if (desc.length() > 200) {
                        desc = desc.substring(0, 200) + "...";
                    }
                    prompt.append(": ").append(desc);
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
                System.out.println("Available commands:");
                System.out.println("  /quit, /exit, exit - Exit the program");
                System.out.println("  /help - Show this help");
                break;
            default:
                System.out.println("Unknown command: " + cmd);
                break;
        }
        
        return false;
    }
    
    /**
     * End the session and perform knowledge extraction.
     * Called when the conversation ends naturally or via /exit.
     */
    public void endSession(boolean completed) {
        logger.info("Ending session: {} (completed={})", sessionId, completed);
        
        // Save trajectory
        if (trajectoryCollector != null) {
            // Add all messages to trajectory
            for (ModelMessage msg : conversationHistory) {
                trajectoryCollector.addMessage(sessionId, msg);
            }
            trajectoryCollector.endSession(sessionId, completed);
        }
        
        // Extract knowledge from session
        if (knowledgeExtractor != null && completed) {
            try {
                KnowledgeExtractor.ExtractionResult result = 
                    knowledgeExtractor.onSessionEnd(sessionId, conversationHistory);
                
                if (!result.getInsights().isEmpty()) {
                    logger.info("Extracted {} insights from session", result.getInsights().size());
                }
                
                if (!result.getMemoriesSaved().isEmpty()) {
                    logger.info("Saved {} memories", result.getMemoriesSaved().size());
                }
                
                if (result.hasSkillCandidate()) {
                    logger.info("Skill candidate extracted (length: {} chars)", 
                        result.getSkillCandidate().length());
                    // TODO: Prompt user to create skill from candidate
                }
                
            } catch (Exception e) {
                logger.error("Knowledge extraction failed: {}", e.getMessage());
            }
        }
        
        // Persist session
        persistSession();
        
        // Shutdown trajectory collector
        if (trajectoryCollector != null) {
            trajectoryCollector.shutdown();
        }
    }
    
    /**
     * Get the trajectory collector for external access.
     */
    public TrajectoryCollector getTrajectoryCollector() {
        return trajectoryCollector;
    }
    
    /**
     * Get the knowledge extractor for external access.
     */
    public KnowledgeExtractor getKnowledgeExtractor() {
        return knowledgeExtractor;
    }
}
