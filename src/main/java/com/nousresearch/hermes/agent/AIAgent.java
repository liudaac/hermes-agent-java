package com.nousresearch.hermes.agent;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashSet;

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
    private MemoryManager memoryManager;  // Non-final for sharing with review agents
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
    
    // Nudge intervals for self-improvement (aligned with Python Hermes)
    private int memoryNudgeInterval = 10;      // Nudge every 10 user turns
    private int skillNudgeInterval = 10;       // Nudge every 10 tool iterations
    private int turnsSinceMemory = 0;          // Counter for memory nudge
    private int itersSinceSkill = 0;           // Counter for skill nudge
    private int userTurnCount = 0;             // Total user turns in session
    
    // Background review prompts (aligned with Python Hermes)
    private static final String MEMORY_REVIEW_PROMPT = 
        "Review the conversation above and consider saving to memory if appropriate.\n\n" +
        "Focus on:\n" +
        "1. Has the user revealed things about themselves — their persona, desires, " +
        "preferences, or personal details worth remembering?\n" +
        "2. Has the user expressed expectations about how you should behave, their work " +
        "style, or ways they want you to operate?\n\n" +
        "If something stands out, save it using the memory tool. " +
        "If nothing is worth saving, just say 'Nothing to save.' and stop.";
    
    private static final String SKILL_REVIEW_PROMPT = 
        "Review the conversation above and consider saving or updating a skill if appropriate.\n\n" +
        "Focus on: was a non-trivial approach used to complete a task that required trial " +
        "and error, or changing course due to experiential findings along the way, or did " +
        "the user expect or desire a different method or outcome?\n\n" +
        "If a relevant skill already exists, update it with what you learned. " +
        "Otherwise, create a new skill if the approach is reusable.\n" +
        "If nothing is worth saving, just say 'Nothing to save.' and stop.";
    
    private static final String COMBINED_REVIEW_PROMPT = 
        "Review the conversation above and consider two things:\n\n" +
        "**Memory**: Has the user revealed things about themselves — their persona, " +
        "desires, preferences, or personal details? Has the user expressed expectations " +
        "about how you should behave, their work style, or ways they want you to operate? " +
        "If so, save using the memory tool.\n\n" +
        "**Skills**: Was a non-trivial approach used to complete a task that required trial " +
        "and error, or changing course due to experiential findings along the way, or did " +
        "the user expect or desire a different method or outcome? If a relevant skill " +
        "already exists, update it. Otherwise, create a new one if the approach is reusable.\n\n" +
        "Only act if there's something genuinely worth saving. " +
        "If nothing stands out, just say 'Nothing to save.' and stop.";
    
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
        
        // Load nudge intervals from config (aligned with Python Hermes)
        loadNudgeConfig();
        
        logger.debug("Initialized learning components");
    }
    
    /**
     * Load nudge interval configuration from config.
     * Mirrors Python: mem_config.get("nudge_interval", 10) and skills_config.get("creation_nudge_interval", 10)
     */
    private void loadNudgeConfig() {
        try {
            // Try to load from ConfigManager if available
            com.nousresearch.hermes.config.ConfigManager cfgMgr = 
                com.nousresearch.hermes.config.ConfigManager.getInstance();
            if (cfgMgr != null) {
                // Memory nudge interval - use getInt with path
                this.memoryNudgeInterval = cfgMgr.getInt("memory.nudge_interval", 10);
                
                // Skill nudge interval - use getInt with path
                this.skillNudgeInterval = cfgMgr.getInt("skills.creation_nudge_interval", 10);
            }
        } catch (Exception e) {
            logger.debug("Failed to load nudge config, using defaults: {}", e.getMessage());
        }
        
        logger.debug("Nudge intervals: memory={}, skill={}", memoryNudgeInterval, skillNudgeInterval);
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
     * Loads all registered tools from ToolRegistry.
     */
    private List<Map<String, Object>> buildToolDefinitions() {
        // Get all registered tool names from the registry
        List<String> allTools = toolRegistry.getAllToolNames();
        
        // Convert to Set for getDefinitions
        Set<String> toolNames = new HashSet<>(allTools);
        
        logger.debug("Building tool definitions for {} tools: {}", toolNames.size(), toolNames);
        
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
        
        // Auto-load skills for CLI mode
        loadAutoSkills("cli");
        
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
        // Track user turns for nudge logic (aligned with Python Hermes)
        userTurnCount++;
        
        // Check memory nudge trigger (turn-based)
        boolean shouldReviewMemory = false;
        if (memoryNudgeInterval > 0 && hasTool("memory")) {
            turnsSinceMemory++;
            if (turnsSinceMemory >= memoryNudgeInterval) {
                shouldReviewMemory = true;
                turnsSinceMemory = 0;
            }
        }
        
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
                    
                    // Track tool-calling iterations for skill nudge (aligned with Python Hermes)
                    // Counter resets when skill_manage is actually used
                    if (skillNudgeInterval > 0 && hasTool("skill_manage")) {
                        itersSinceSkill++;
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
        
        // Check skill trigger NOW - based on how many tool iterations THIS turn used
        // (aligned with Python Hermes)
        boolean shouldReviewSkills = false;
        if (skillNudgeInterval > 0 && itersSinceSkill >= skillNudgeInterval && hasTool("skill_manage")) {
            shouldReviewSkills = true;
            itersSinceSkill = 0;
        }
        
        // Background memory/skill review - runs AFTER the response is delivered
        // so it never competes with the user's task for model attention
        String finalResponse = responseBuilder.toString();
        if (!finalResponse.isEmpty() && !interrupted.get() && (shouldReviewMemory || shouldReviewSkills)) {
            spawnBackgroundReview(new ArrayList<>(conversationHistory), shouldReviewMemory, shouldReviewSkills);
        }
        
        return finalResponse;
    }
    
    /**
     * Process a user message through the conversation loop (interactive CLI mode).
     */
    private void processUserMessage(String userInput) {
        // Track user turns for nudge logic (aligned with Python Hermes)
        userTurnCount++;
        
        // Check memory nudge trigger (turn-based)
        boolean shouldReviewMemory = false;
        if (memoryNudgeInterval > 0 && hasTool("memory")) {
            turnsSinceMemory++;
            if (turnsSinceMemory >= memoryNudgeInterval) {
                shouldReviewMemory = true;
                turnsSinceMemory = 0;
            }
        }
        
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
                    
                    // Track tool-calling iterations for skill nudge (aligned with Python Hermes)
                    // Counter resets when skill_manage is actually used
                    if (skillNudgeInterval > 0 && hasTool("skill_manage")) {
                        itersSinceSkill++;
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
        
        // Check skill trigger NOW - based on how many tool iterations THIS turn used
        // (aligned with Python Hermes)
        boolean shouldReviewSkills = false;
        if (skillNudgeInterval > 0 && itersSinceSkill >= skillNudgeInterval && hasTool("skill_manage")) {
            shouldReviewSkills = true;
            itersSinceSkill = 0;
        }
        
        // Background memory/skill review - runs AFTER the response is delivered
        // so it never competes with the user's task for model attention
        if (!interrupted.get() && (shouldReviewMemory || shouldReviewSkills)) {
            spawnBackgroundReview(new ArrayList<>(conversationHistory), shouldReviewMemory, shouldReviewSkills);
        }
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
        
        // Add available skills info
        prompt.append("\n").append(buildSkillsPrompt());
        
        return prompt.toString();
    }
    
    /**
     * Build the skills section of the system prompt.
     * Injects available skills list to guide the agent to use them.
     */
    private String buildSkillsPrompt() {
        if (skillManager == null) {
            return "";
        }
        
        List<SkillManager.Skill> skills = skillManager.listSkills();
        if (skills.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## Skills (mandatory)\n\n");
        sb.append("Before replying, scan the skills below. If one clearly matches your task, ");
        sb.append("load it with skill_get(name) and follow its instructions. ");
        sb.append("If a skill has issues, fix it with skill_update().\n");
        sb.append("After difficult/iterative tasks, offer to save as a skill.\n");
        sb.append("If a skill you loaded was missing steps, had wrong commands, or needed ");
        sb.append("pitfalls you discovered, update it before finishing.\n\n");
        
        sb.append("<available_skills>\n");
        
        // Group by category (using tags as categories)
        Map<String, List<SkillManager.Skill>> byCategory = new HashMap<>();
        for (SkillManager.Skill skill : skills) {
            String category = skill.tags.isEmpty() ? "general" : skill.tags.get(0);
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(skill);
        }
        
        // Sort categories and skills
        List<String> categories = new ArrayList<>(byCategory.keySet());
        Collections.sort(categories);
        
        for (String category : categories) {
            sb.append("  ").append(category).append(":\n");
            List<SkillManager.Skill> catSkills = byCategory.get(category);
            catSkills.sort(Comparator.comparing(s -> s.name));
            
            for (SkillManager.Skill skill : catSkills) {
                sb.append("    - ").append(skill.name);
                if (skill.description != null && !skill.description.isEmpty()) {
                    String desc = skill.description;
                    if (desc.length() > 80) {
                        desc = desc.substring(0, 80) + "...";
                    }
                    sb.append(": ").append(desc);
                }
                sb.append("\n");
            }
        }
        
        sb.append("</available_skills>\n\n");
        sb.append("If none match, proceed normally without loading a skill.");
        
        return sb.toString();
    }
    
    /**
     * Handle slash commands.
     * Supports:
     * - /quit, /exit, exit - Exit the program
     * - /help - Show help
     * - /skill-name - Quick load and execute a skill
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
            default:
                // Check for skill shortcut: /skill-name
                if (cmd.startsWith("/") && !cmd.startsWith("//")) {
                    String skillName = cmd.substring(1).trim();
                    if (!skillName.isEmpty()) {
                        handleSkillShortcut(skillName);
                        return false;
                    }
                }
                System.out.println("Unknown command: " + cmd);
                break;
        }
        
        return false;
    }
    
    /**
     * Print help information.
     */
    private void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  /quit, /exit, exit - Exit the program");
        System.out.println("  /help              - Show this help");
        System.out.println("  /skill-name        - Quick load and execute a skill (e.g., /web-search)");
        System.out.println();
        System.out.println("Available skills:");
        if (skillManager != null) {
            List<SkillManager.Skill> skills = skillManager.listSkills();
            if (skills.isEmpty()) {
                System.out.println("  (No skills available)");
            } else {
                for (SkillManager.Skill skill : skills) {
                    System.out.println("  /" + skill.name + " - " + 
                        (skill.description != null ? skill.description : "No description"));
                }
            }
        }
    }
    
    /**
     * Load auto-skills for a channel/session.
     * Called during initialization to preload configured skills.
     */
    private void loadAutoSkills(String channelId) {
        if (skillManager == null || config == null) {
            return;
        }
        
        List<String> autoSkills = config.getAutoSkills(channelId);
        if (autoSkills.isEmpty()) {
            return;
        }
        
        logger.info("Auto-loading {} skills for {}", autoSkills.size(), channelId);
        
        for (String skillName : autoSkills) {
            SkillManager.Skill skill = skillManager.loadSkill(skillName);
            if (skill != null) {
                // Build skill instruction message
                StringBuilder skillPrompt = new StringBuilder();
                skillPrompt.append("=== AUTO-LOADED SKILL: ").append(skill.name).append(" ===\n\n");
                if (skill.description != null) {
                    skillPrompt.append("Description: ").append(skill.description).append("\n\n");
                }
                skillPrompt.append(skill.content);
                skillPrompt.append("\n\n=== END SKILL ===");
                
                // Add as system message
                conversationHistory.add(ModelMessage.system(skillPrompt.toString()));
                skillManager.recordUsage(skillName);
                
                logger.debug("Auto-loaded skill: {}", skillName);
            } else {
                logger.warn("Auto-skill not found: {}", skillName);
            }
        }
    }
    
    /**
     * Handle skill shortcut command (/skill-name).
     * Loads the skill and injects it into the conversation.
     */
    private void handleSkillShortcut(String skillName) {
        if (skillManager == null) {
            System.out.println("Skill manager not available");
            return;
        }
        
        SkillManager.Skill skill = skillManager.loadSkill(skillName);
        if (skill == null) {
            System.out.println("Skill not found: " + skillName);
            System.out.println("Use /help to see available skills");
            return;
        }
        
        System.out.println("Loading skill: " + skill.name);
        
        // Record usage
        skillManager.recordUsage(skillName);
        
        // Build skill instruction message
        StringBuilder skillPrompt = new StringBuilder();
        skillPrompt.append("=== SKILL: ").append(skill.name).append(" ===\n\n");
        if (skill.description != null) {
            skillPrompt.append("Description: ").append(skill.description).append("\n\n");
        }
        skillPrompt.append(skill.content);
        skillPrompt.append("\n\n=== END SKILL ===");
        
        // Add as system message
        conversationHistory.add(ModelMessage.system(skillPrompt.toString()));
        
        System.out.println("Skill loaded. You can now ask me to perform tasks using this skill.");
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
    
    /**
     * Check if a tool is available in the tool registry.
     * @param toolName The name of the tool to check
     * @return true if the tool is available
     */
    private boolean hasTool(String toolName) {
        if (toolDefinitions == null || toolDefinitions.isEmpty()) {
            return false;
        }
        for (Map<String, Object> tool : toolDefinitions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            if (function != null && toolName.equals(function.get("name"))) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Spawn a background thread to review the conversation for memory/skill saves.
     * Mirrors Python Hermes: _spawn_background_review()
     * 
     * Creates a forked AIAgent with the same model and context. The review prompt
     * is appended as the next user turn. Writes directly to shared memory/skill stores.
     * Never modifies the main conversation history or produces user-visible output.
     * 
     * @param messagesSnapshot Copy of conversation history to review
     * @param reviewMemory Whether to review for memory saves
     * @param reviewSkills Whether to review for skill saves
     */
    private void spawnBackgroundReview(
        List<ModelMessage> messagesSnapshot,
        boolean reviewMemory,
        boolean reviewSkills
    ) {
        // Pick the right prompt based on which triggers fired
        final String prompt;
        if (reviewMemory && reviewSkills) {
            prompt = COMBINED_REVIEW_PROMPT;
        } else if (reviewMemory) {
            prompt = MEMORY_REVIEW_PROMPT;
        } else {
            prompt = SKILL_REVIEW_PROMPT;
        }
        
        // Spawn background thread
        Thread reviewThread = new Thread(() -> {
            try {
                logger.debug("Starting background review (memory={}, skills={})", reviewMemory, reviewSkills);
                
                // Create a quiet review agent with same config but limited iterations
                // Clone the current config and override max turns
                HermesConfig reviewConfig = HermesConfig.load();
                reviewConfig.setModelOverride(config.getCurrentModel());
                // Note: max_turns is controlled by IterationBudget, not HermesConfig directly
                
                AIAgent reviewAgent = new AIAgent(reviewConfig, "review_" + sessionId);
                
                // Share memory and skill stores with main agent
                reviewAgent.memoryManager = this.memoryManager;
                reviewAgent.skillManager = this.skillManager;
                reviewAgent.memoryNudgeInterval = 0; // Disable nudges in review agent
                reviewAgent.skillNudgeInterval = 0;
                // Override iteration budget for review agent (max 8 iterations)
                reviewAgent.iterationBudget.setMaxIterations(8);
                
                // Run review conversation
                reviewAgent.processMessage(prompt);
                
                // Scan review agent's messages for successful tool actions
                List<String> actions = new ArrayList<>();
                for (ModelMessage msg : reviewAgent.conversationHistory) {
                    if (!"tool".equals(msg.getRole())) {
                        continue;
                    }
                    String content = msg.getContent();
                    if (content == null || content.isEmpty()) {
                        continue;
                    }
                    
                    // Check for success indicators in tool results
                    String lowerContent = content.toLowerCase();
                    if (lowerContent.contains("\"success\": true") || 
                        lowerContent.contains("\"success\":true")) {
                        
                        // Extract action message
                        if (lowerContent.contains("created")) {
                            actions.add(extractActionMessage(content, "created"));
                        } else if (lowerContent.contains("updated")) {
                            actions.add(extractActionMessage(content, "updated"));
                        } else if (lowerContent.contains("added")) {
                            actions.add(extractActionMessage(content, "added"));
                        } else if (lowerContent.contains("removed") || lowerContent.contains("replaced")) {
                            actions.add(extractActionMessage(content, "updated"));
                        }
                    }
                }
                
                // Surface summary to user if actions were taken
                if (!actions.isEmpty()) {
                    // Remove duplicates while preserving order
                    List<String> uniqueActions = new ArrayList<>(new LinkedHashSet<>(actions));
                    String summary = String.join(" · ", uniqueActions);
                    System.out.println("  💾 " + summary);
                    logger.info("Background review completed: {}", summary);
                } else {
                    logger.debug("Background review completed - no actions taken");
                }
                
            } catch (Exception e) {
                logger.debug("Background memory/skill review failed: {}", e.getMessage());
            }
        }, "bg-review");
        
        reviewThread.setDaemon(true);
        reviewThread.start();
    }
    
    /**
     * Extract action message from tool result content.
     * Helper for background review summary.
     */
    private String extractActionMessage(String content, String actionType) {
        try {
            // Try to parse JSON and extract message
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(content, Map.class);
            
            String message = (String) result.get("message");
            if (message != null && !message.isEmpty()) {
                return message;
            }
            
            // Fallback: construct message from target
            String target = (String) result.get("target");
            if ("memory".equals(target)) {
                return "Memory " + actionType;
            } else if ("user".equals(target)) {
                return "User profile " + actionType;
            } else if (target != null) {
                return target + " " + actionType;
            }
        } catch (Exception e) {
            // Fallback to simple extraction
            if (content.toLowerCase().contains("memory")) {
                return "Memory " + actionType;
            } else if (content.toLowerCase().contains("skill")) {
                return "Skill " + actionType;
            }
        }
        return "Entry " + actionType;
    }
}
