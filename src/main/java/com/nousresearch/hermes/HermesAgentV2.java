package com.nousresearch.hermes;

import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.gateway.GatewayServer;
import com.nousresearch.hermes.gateway.SessionManager;
import com.nousresearch.hermes.gateway.platforms.*;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.ToolInitializerV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Enhanced Hermes Agent main class with all new features.
 */
public class HermesAgentV2 {
    private static final Logger logger = LoggerFactory.getLogger(HermesAgentV2.class);
    
    private final ConfigManager config;
    private final ApprovalSystem approvalSystem;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final GatewayServer gatewayServer;
    private AIAgent interactiveAgent;  // Persistent agent for interactive mode
    
    public HermesAgentV2() throws Exception {
        // Load configuration
        this.config = ConfigManager.getInstance();
        config.load();
        
        // Initialize approval system
        this.approvalSystem = new ApprovalSystem();
        
        // Initialize tool registry
        this.toolRegistry = ToolRegistry.getInstance();
        ToolInitializerV2.initializeAll(toolRegistry, approvalSystem);
        
        // Initialize session manager
        Path dataDir = Paths.get(System.getProperty("user.home"), ".hermes");
        this.sessionManager = new SessionManager(dataDir);
        
        // Initialize gateway server
        int gatewayPort = config.getInt("gateway.port", 8080);
        HermesConfig agentConfig = new HermesConfig(
            config.getApiKey(),
            config.getBaseUrl(),
            config.getModelName()
        );
        this.gatewayServer = new GatewayServer(gatewayPort, agentConfig);
        
        // Register platform adapters
        registerAdapters();
        
        logger.info("Hermes Agent V2 initialized");
    }
    
    private void registerAdapters() {
        // Feishu
        if (System.getenv("FEISHU_APP_ID") != null) {
            gatewayServer.registerAdapter(new FeishuAdapterV2());
            logger.info("Feishu adapter registered");
        }
        
        // Telegram
        if (System.getenv("TELEGRAM_BOT_TOKEN") != null) {
            gatewayServer.registerAdapter(new TelegramAdapter());
            logger.info("Telegram adapter registered");
        }
        
        // Discord
        if (System.getenv("DISCORD_BOT_TOKEN") != null) {
            gatewayServer.registerAdapter(new DiscordAdapter());
            logger.info("Discord adapter registered");
        }
    }
    
    /**
     * Start the agent.
     */
    public void start() {
        // Start gateway server
        gatewayServer.start();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            gatewayServer.stop();
            sessionManager.persistAll();
        }));
        
        logger.info("Hermes Agent V2 started");
    }
    
    /**
     * Run in interactive mode.
     */
    public void runInteractive() {
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║      Hermes Agent V2 - Ready         ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("Type 'exit' to quit, 'help' for commands\n");
        
        while (true) {
            System.out.print("hermes> ");
            String input = scanner.nextLine().trim();
            
            if (input.equalsIgnoreCase("exit")) {
                break;
            }
            
            if (input.equalsIgnoreCase("help")) {
                printHelp();
                continue;
            }
            
            if (input.equalsIgnoreCase("tools")) {
                listTools();
                continue;
            }
            
            if (input.startsWith("/")) {
                handleCommand(input);
                continue;
            }
            
            // Process as message
            if (!processMessage(input)) {
                break; // Exit command received
            }
        }
        
        // Cleanup
        scanner.close();
        if (interactiveAgent != null) {
            interactiveAgent.endSession(true);
        }
        System.out.println("\nGoodbye! 👋");
    }
    
    private void printHelp() {
        System.out.println("Commands:");
        System.out.println("  exit     - Quit the agent");
        System.out.println("  help     - Show this help");
        System.out.println("  tools    - List available tools");
        System.out.println("  /config  - Show configuration");
        System.out.println("  /status  - Show agent status");
    }
    
    private void listTools() {
        System.out.println("Available tools:");
        for (var tool : toolRegistry.getAllTools()) {
            System.out.println("  " + tool.getEmoji() + " " + tool.getName() + " - " + tool.getDescription());
        }
    }
    
    private void handleCommand(String cmd) {
        switch (cmd) {
            case "/config" -> config.printAll();
            case "/status" -> printStatus();
            default -> System.out.println("Unknown command: " + cmd);
        }
    }
    
    private void printStatus() {
        System.out.println("Agent Status:");
        System.out.println("  Config: " + (config != null ? "loaded" : "not loaded"));
        System.out.println("  Tools: " + toolRegistry.getAllTools().size());
        System.out.println("  Session Manager: " + (sessionManager != null ? "active" : "inactive"));
    }
    
    private boolean processMessage(String message) {
        // Check for exit command
        if (message.equalsIgnoreCase("exit") || message.equalsIgnoreCase("quit")) {
            return false; // Signal to exit
        }
        
        try {
            logger.info("Processing message: {}", message.substring(0, Math.min(50, message.length())));
            
            // Initialize persistent agent on first message
            if (interactiveAgent == null) {
                HermesConfig agentConfig = new HermesConfig(
                    config.getApiKey(),
                    config.getBaseUrl(),
                    config.getModelName()
                );
                interactiveAgent = new AIAgent(agentConfig);
                logger.info("Created persistent AIAgent for interactive mode");
            }
            
            // Process the message and get response
            String response = interactiveAgent.processMessage(message);
            
            // Display the response
            System.out.println("\n🤖 " + response + "\n");
            
        } catch (Exception e) {
            logger.error("Failed to process message: {}", e.getMessage(), e);
            System.out.println("❌ Error: " + e.getMessage());
        }
        
        return true; // Continue running
    }
    
    public static void main(String[] args) {
        try {
            HermesAgentV2 agent = new HermesAgentV2();
            agent.start();
            
            // Run interactive if no gateway adapters configured
            if (System.getenv("FEISHU_APP_ID") == null && 
                System.getenv("TELEGRAM_BOT_TOKEN") == null &&
                System.getenv("DISCORD_BOT_TOKEN") == null) {
                agent.runInteractive();
            } else {
                // Keep running for gateway mode
                Thread.currentThread().join();
            }
            
        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
