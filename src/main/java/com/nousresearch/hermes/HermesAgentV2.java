package com.nousresearch.hermes;

import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.dashboard.DashboardServer;
import com.nousresearch.hermes.dashboard.GatewayRuntimeStatus;
import com.nousresearch.hermes.gateway.GatewayServer;
import com.nousresearch.hermes.gateway.GatewayServerV2;
import com.nousresearch.hermes.gateway.SessionManager;
import com.nousresearch.hermes.gateway.platforms.*;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.ToolInitializerV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Enhanced Hermes Agent main class with unified tenant mode.
 */
public class HermesAgentV2 {
    private static final Logger logger = LoggerFactory.getLogger(HermesAgentV2.class);

    private final ConfigManager config;
    private final ApprovalSystem approvalSystem;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final TenantManager tenantManager;
    private GatewayServer gatewayServer;       // Legacy gateway (V1)
    private GatewayServerV2 gatewayServerV2;   // New tenant-aware gateway (V2)
    private DashboardServer dashboardServer;   // Dashboard web UI
    private final boolean tenantMode;
    private TenantAwareAIAgent interactiveAgent;  // Persistent agent for interactive mode
    
    public HermesAgentV2() throws Exception {
        this(true); // Default to tenant mode for full feature support
    }

    /**
     * Constructor with tenant mode option.
     * @param tenantMode true to enable unified tenant mode
     */
    public HermesAgentV2(boolean tenantMode) throws Exception {
        this.tenantMode = tenantMode;

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

        // Initialize tenant manager (always available, used in both modes)
        this.tenantManager = new TenantManager();
        if (tenantMode) {
            // Initialize default tenant for single-user scenario
            tenantManager.initializeDefaultTenant();
            logger.info("Tenant mode initialized with default tenant");
        }

        // Initialize gateway server based on mode
        int gatewayPort = config.getInt("gateway.port", 8080);
        HermesConfig agentConfig = new HermesConfig(
            config.getApiKey(),
            config.getBaseUrl(),
            config.getModelName()
        );

        if (tenantMode) {
            this.gatewayServerV2 = new GatewayServerV2(gatewayPort, agentConfig);
            this.gatewayServer = null;
            // Register platform adapters for V2
            registerAdaptersV2();
        } else {
            this.gatewayServer = new GatewayServer(gatewayPort, agentConfig);
            this.gatewayServerV2 = null;
            // Register platform adapters for V1
            registerAdapters();
        }

        logger.info("Hermes Agent V2 initialized (tenant mode: {})", tenantMode);
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

    private void registerAdaptersV2() {
        // Feishu (V2 with tenant support)
        if (System.getenv("FEISHU_APP_ID") != null) {
            gatewayServerV2.registerAdapter(new FeishuAdapterV2());
            logger.info("Feishu adapter registered (V2)");
        }

        // Telegram (V2 with tenant support)
        if (System.getenv("TELEGRAM_BOT_TOKEN") != null) {
            gatewayServerV2.registerAdapter(new TelegramAdapter());
            logger.info("Telegram adapter registered (V2)");
        }

        // Discord (V2 with tenant support)
        if (System.getenv("DISCORD_BOT_TOKEN") != null) {
            gatewayServerV2.registerAdapter(new DiscordAdapter());
            logger.info("Discord adapter registered (V2)");
        }
    }
    
    /**
     * Start the agent.
     */
    public void start() {
        // Start gateway server based on mode
        if (tenantMode && gatewayServerV2 != null) {
            gatewayServerV2.start();
            logger.info("Gateway V2 started (tenant mode)");
        } else if (gatewayServer != null) {
            gatewayServer.start();
            logger.info("Gateway V1 started (legacy mode)");
        }

        // Start dashboard server
        startDashboard();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            if (dashboardServer != null) {
                try {
                    dashboardServer.stop();
                    logger.info("Dashboard server stopped");
                } catch (Exception e) {
                    logger.error("Error stopping dashboard server: {}", e.getMessage());
                }
            }
            if (gatewayServerV2 != null) {
                gatewayServerV2.stop();
            }
            if (gatewayServer != null) {
                gatewayServer.stop();
            }
            sessionManager.persistAll();
            if (tenantManager != null) {
                tenantManager.shutdown();
            }
        }));

        logger.info("Hermes Agent V2 started (tenant mode: {})", tenantMode);
    }

    /**
     * Start the dashboard server for web UI.
     */
    private void startDashboard() {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("HERMES_DASHBOARD_PORT", "9119"));
            String host = System.getenv().getOrDefault("HERMES_DASHBOARD_HOST", "127.0.0.1");
            HermesConfig agentConfig = new HermesConfig(
                config.getApiKey(),
                config.getBaseUrl(),
                config.getModelName()
            );
            dashboardServer = new DashboardServer(port, host, agentConfig, tenantManager, this::getDashboardRuntimeStatus);
            dashboardServer.start();
            logger.info("Dashboard server started on http://{}:{}", host, port);

            // Share session token with GatewayServerV2 so the dashboard UI
            // can call /api/chat endpoints with the same Bearer token.
            if (gatewayServerV2 != null && dashboardServer.getSessionToken() != null) {
                gatewayServerV2.setSessionToken(dashboardServer.getSessionToken());
            }
        } catch (Exception e) {
            logger.error("Failed to start dashboard server: {}", e.getMessage(), e);
        }
    }

    /**
     * Provide runtime status for the dashboard.
     */
    private GatewayRuntimeStatus getDashboardRuntimeStatus() {
        boolean gatewayRunning = gatewayServerV2 != null || gatewayServer != null;
        Integer port = null;
        if (gatewayServerV2 != null) {
            // GatewayServerV2 doesn't expose port directly, use config
            port = config.getInt("gateway.port", 8080);
        } else if (gatewayServer != null) {
            port = config.getInt("gateway.port", 8080);
        }
        return new GatewayRuntimeStatus(
            gatewayRunning,
            port,
            gatewayRunning ? "RUNNING" : "STOPPED",
            gatewayRunning && port != null ? "http://127.0.0.1:" + port + "/health" : null,
            null,
            System.currentTimeMillis(),
            java.util.List.of()
        );
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
                // Use TenantAwareAIAgent (works in both tenant and non-tenant modes)
                interactiveAgent = TenantAwareAIAgent.createDefault(agentConfig);
                logger.info("Created persistent TenantAwareAIAgent for interactive mode (tenant: {})",
                    interactiveAgent.getTenantId());
            }

            // Process the message and get response
            String response = interactiveAgent.processMessage(message);

            // Display the response
            if (response != null && !response.isEmpty()) {
                System.out.println("\n🤖 " + response + "\n");
            }

        } catch (Exception e) {
            logger.error("Failed to process message: {}", e.getMessage(), e);
            System.out.println("❌ Error: " + e.getMessage());
        }

        return true; // Continue running
    }
    
    public static void main(String[] args) {
        try {
            // Check for tenant mode flag
            boolean tenantMode = false;
            for (String arg : args) {
                if (arg.equals("--tenant") || arg.equals("-t")) {
                    tenantMode = true;
                    break;
                }
            }

            // Also check environment variable
            if (System.getenv("HERMES_TENANT_MODE") != null) {
                tenantMode = Boolean.parseBoolean(System.getenv("HERMES_TENANT_MODE"));
            }

            logger.info("Starting Hermes Agent V2 with tenant mode: {}", tenantMode);
            HermesAgentV2 agent = new HermesAgentV2(tenantMode);
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
