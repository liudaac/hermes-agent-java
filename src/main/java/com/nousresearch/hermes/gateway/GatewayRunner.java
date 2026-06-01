package com.nousresearch.hermes.gateway;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.dashboard.DashboardServer;
import com.nousresearch.hermes.dashboard.GatewayRuntimeStatus;
import com.nousresearch.hermes.gateway.platforms.DiscordAdapter;
import com.nousresearch.hermes.gateway.platforms.FeishuAdapter;
import com.nousresearch.hermes.gateway.platforms.TelegramAdapter;
import com.nousresearch.hermes.gateway.platforms.feishu.FeishuCommentAdapter;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gateway runner - manages messaging platform integrations.
 * Coordinates multiple platform adapters (Telegram, Discord, Feishu, etc.)
 */
public class GatewayRunner {
    private static final Logger logger = LoggerFactory.getLogger(GatewayRunner.class);
    
    private final HermesConfig config;
    private final List<PlatformAdapter> adapters;
    private volatile boolean running;
    private DashboardServer dashboardServer;
    private GatewayServerV2 gatewayServer;
    private Integer gatewayPort;
    private final TenantManager tenantManager;
    private final Integer gatewayPortOverride;
    
    public GatewayRunner(HermesConfig config) {
        this(config, null);
    }

    public GatewayRunner(HermesConfig config, Integer gatewayPortOverride) {
        this.config = config;
        this.gatewayPortOverride = gatewayPortOverride;
        this.tenantManager = new TenantManager();
        this.adapters = new ArrayList<>();
        this.running = false;
    }
    
    /**
     * Run gateway in foreground.
     */
    public void runForeground() {
        logger.info("Starting Hermes Gateway...");
        
        try {
            initializeAdapters();
            startAdapters();
            startGatewayServer();

            // Start Dashboard server after the webhook/API gateway is listening.
            startDashboard();
            
            running = true;
            System.out.println("Gateway is running. Press Ctrl+C to stop.");
            
            // Wait for shutdown signal
            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown signal received");
                latch.countDown();
            }));
            
            latch.await();
            
        } catch (InterruptedException e) {
            logger.info("Gateway interrupted");
        } catch (Exception e) {
            logger.error("Gateway error: {}", e.getMessage(), e);
        } finally {
            stopGatewayServer();
            stopAdapters();
            stopDashboard();
            tenantManager.shutdown();
            running = false;
        }
        
        logger.info("Gateway stopped");
    }

    /**
     * Start the tenant-aware HTTP webhook/API gateway and register platform adapters.
     */
    private void startGatewayServer() {
        int port = gatewayPortOverride != null
            ? gatewayPortOverride
            : Integer.parseInt(System.getenv().getOrDefault("HERMES_GATEWAY_PORT", "8080"));
        gatewayPort = port;
        gatewayServer = new GatewayServerV2(port, config, tenantManager);
        for (PlatformAdapter adapter : adapters) {
            gatewayServer.registerAdapter(adapter);
        }
        gatewayServer.start();
        logger.info("Gateway server started on port {} with {} adapter(s)", port, adapters.size());
    }

    /**
     * Stop the tenant-aware HTTP webhook/API gateway.
     */
    private void stopGatewayServer() {
        if (gatewayServer != null) {
            try {
                gatewayServer.stop();
                logger.info("Gateway server stopped");
            } catch (Exception e) {
                logger.error("Error stopping gateway server: {}", e.getMessage());
            } finally {
                gatewayServer = null;
                gatewayPort = null;
            }
        }
    }

    /**
     * Start the dashboard server.
     */
    private void startDashboard() {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("HERMES_DASHBOARD_PORT", "9119"));
            String host = System.getenv().getOrDefault("HERMES_DASHBOARD_HOST", "127.0.0.1");
            
            dashboardServer = new DashboardServer(port, host, config, tenantManager, this::getRuntimeStatus);
            dashboardServer.start();
            
            logger.info("Dashboard server started on http://{}:{}", host, port);
        } catch (Exception e) {
            logger.error("Failed to start dashboard server: {}", e.getMessage());
        }
    }

    /**
     * Stop the dashboard server.
     */
    private void stopDashboard() {
        if (dashboardServer != null) {
            try {
                dashboardServer.stop();
                logger.info("Dashboard server stopped");
            } catch (Exception e) {
                logger.error("Error stopping dashboard server: {}", e.getMessage());
            }
        }
    }
    
    private GatewayRuntimeStatus getRuntimeStatus() {
        boolean gatewayRunning = gatewayServer != null;
        Integer port = gatewayPort;
        return new GatewayRuntimeStatus(
            gatewayRunning,
            port,
            gatewayRunning ? "RUNNING" : "STOPPED",
            gatewayRunning && port != null ? "http://127.0.0.1:" + port + "/health" : null,
            null,
            System.currentTimeMillis(),
            adapters.stream().map(PlatformAdapter::getPlatformName).toList()
        );
    }

    /**
     * Start gateway as service (background).
     */
    private static final Path PID_FILE = Path.of(System.getProperty("user.home"), ".hermes", "gateway.pid");
    
    public void startService() {
        try {
            // Check if already running
            if (Files.exists(PID_FILE)) {
                String pid = Files.readString(PID_FILE).trim();
                logger.error("Gateway service already running with PID: {}", pid);
                System.out.println("Gateway service already running (PID: " + pid + ")");
                return;
            }
            
            // Create PID file
            Files.createDirectories(PID_FILE.getParent());
            long currentPid = ProcessHandle.current().pid();
            Files.writeString(PID_FILE, String.valueOf(currentPid));
            logger.info("Starting gateway service with PID: {}", currentPid);
            
            // Run in background thread
            Thread serviceThread = new Thread(() -> {
                try {
                    runForeground();
                } finally {
                    try { Files.deleteIfExists(PID_FILE); } catch (IOException e) {}
                }
            }, "hermes-gateway-service");
            serviceThread.setDaemon(false);
            serviceThread.start();
            
            System.out.println("Gateway service started (PID: " + currentPid + ")");
        } catch (Exception e) {
            logger.error("Failed to start gateway service: {}", e.getMessage(), e);
            System.out.println("Failed to start gateway service: " + e.getMessage());
        }
    }
    
    /**
     * Stop gateway service.
     */
    public void stopService() {
        try {
            if (!Files.exists(PID_FILE)) {
                logger.warn("No PID file found");
                System.out.println("Gateway service not running");
                return;
            }
            
            String pidStr = Files.readString(PID_FILE).trim();
            long pid = Long.parseLong(pidStr);
            
            ProcessHandle.of(pid).ifPresentOrElse(
                process -> {
                    logger.info("Sending termination signal to PID: {}", pid);
                    process.destroy();
                    System.out.println("Sent termination signal to gateway");
                    try { Thread.sleep(2000); } catch (InterruptedException e) {}
                    if (process.isAlive()) process.destroyForcibly();
                },
                () -> System.out.println("Process not found")
            );
            
            Files.deleteIfExists(PID_FILE);
            logger.info("Gateway service stopped");
        } catch (Exception e) {
            logger.error("Failed to stop gateway service: {}", e.getMessage(), e);
            System.out.println("Failed to stop gateway service: " + e.getMessage());
        }
    }
    
    /**
     * Show gateway status.
     */
    public void showStatus() {
        System.out.println("Gateway Status:");
        System.out.println("  Running: " + running);
        System.out.println("  Adapters: " + adapters.size());
        for (PlatformAdapter adapter : adapters) {
            System.out.println("    - " + adapter.getPlatformName() + ": connected");
        }
        if (gatewayServer != null) {
            System.out.println("  Gateway API: running");
        } else {
            System.out.println("  Gateway API: not started");
        }
        if (dashboardServer != null) {
            System.out.println("  Dashboard: running");
        } else {
            System.out.println("  Dashboard: not started");
        }
    }
    
    /**
     * Initialize platform adapters.
     */
    private void initializeAdapters() {
        // Get enabled platforms from config
        // For now, just log that we would initialize adapters
        logger.info("Initializing platform adapters...");
        
        // In a full implementation, this would:
        // - Check config for enabled platforms
        // - Create adapter instances
        // - Register them with the adapter list
        
        // Initialize enabled platform adapters
        if (isPlatformEnabled("telegram")) {
            try {
                adapters.add(new TelegramAdapter(config));
                logger.info("Initialized Telegram adapter");
            } catch (Exception e) {
                logger.error("Failed to initialize Telegram adapter: {}", e.getMessage());
            }
        }
        if (isPlatformEnabled("feishu")) {
            try {
                adapters.add(new FeishuAdapter(config));
                logger.info("Initialized Feishu adapter");
            } catch (Exception e) {
                logger.error("Failed to initialize Feishu adapter: {}", e.getMessage());
            }
        }
        if (isPlatformEnabled("feishu_comment")) {
            try {
                adapters.add(new FeishuCommentAdapter());
                logger.info("Initialized Feishu Comment adapter");
            } catch (Exception e) {
                logger.error("Failed to initialize Feishu Comment adapter: {}", e.getMessage());
            }
        }
        if (isPlatformEnabled("discord")) {
            try {
                adapters.add(new DiscordAdapter(config));
                logger.info("Initialized Discord adapter");
            } catch (Exception e) {
                logger.error("Failed to initialize Discord adapter: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Start all adapters.
     */
    private void startAdapters() {
        Iterator<PlatformAdapter> iterator = adapters.iterator();
        while (iterator.hasNext()) {
            PlatformAdapter adapter = iterator.next();
            try {
                logger.info("Starting {} adapter...", adapter.getPlatformName());
                if (adapter instanceof com.nousresearch.hermes.gateway.platforms.PlatformAdapter lifecycleAdapter) {
                    lifecycleAdapter.start();
                    if (!lifecycleAdapter.isConnected()) {
                        logger.warn("{} adapter started but is not connected; removing from gateway registry", adapter.getPlatformName());
                        iterator.remove();
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to start {} adapter: {}", adapter.getPlatformName(), e.getMessage());
                iterator.remove();
            }
        }
    }
    
    /**
     * Stop all adapters.
     */
    private void stopAdapters() {
        for (PlatformAdapter adapter : adapters) {
            try {
                logger.info("Stopping {} adapter...", adapter.getPlatformName());
                if (adapter instanceof com.nousresearch.hermes.gateway.platforms.PlatformAdapter lifecycleAdapter) {
                    lifecycleAdapter.stop();
                }
            } catch (Exception e) {
                logger.error("Error stopping {} adapter: {}", adapter.getPlatformName(), e.getMessage());
            }
        }

    }
    
    /**
     * Check if a platform is enabled in config.
     */
    private boolean isPlatformEnabled(String platform) {
        String normalized = platform.toLowerCase(Locale.ROOT);

        // If gateway.enabled_platforms is set, treat it as an allowlist.
        List<String> configuredPlatforms = config.getStringList("gateway.enabled_platforms").stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .toList();
        if (!configuredPlatforms.isEmpty() && !configuredPlatforms.contains(normalized)) {
            logger.debug("Platform {} disabled by gateway.enabled_platforms", normalized);
            return false;
        }

        // If a per-platform flag is present, respect it.
        if (!config.getBoolean("gateway." + normalized + ".enabled", true)) {
            logger.debug("Platform {} disabled by gateway.{}.enabled", normalized, normalized);
            return false;
        }

        // Do not register platforms that cannot send replies in this process.
        boolean credentialed = hasRequiredCredentials(normalized);
        if (!credentialed) {
            logger.info("Skipping {} adapter: required credentials are not configured", normalized);
        }
        return credentialed;
    }

    private boolean hasRequiredCredentials(String platform) {
        return switch (platform) {
            case "telegram" -> hasEnv("TELEGRAM_BOT_TOKEN");
            case "feishu", "feishu_comment" -> hasEnv("FEISHU_APP_ID") && hasEnv("FEISHU_APP_SECRET");
            case "discord" -> hasEnv("DISCORD_BOT_TOKEN");
            default -> false;
        };
    }

    private boolean hasEnv(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}
