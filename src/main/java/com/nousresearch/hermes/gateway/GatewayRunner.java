package com.nousresearch.hermes.gateway;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.dashboard.DashboardServer;
import com.nousresearch.hermes.gateway.platforms.DiscordAdapter;
import com.nousresearch.hermes.gateway.platforms.FeishuAdapter;
import com.nousresearch.hermes.gateway.platforms.TelegramAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Gateway runner - manages messaging platform integrations.
 * Coordinates multiple platform adapters (Telegram, Discord, Feishu, etc.)
 */
public class GatewayRunner {
    private static final Logger logger = LoggerFactory.getLogger(GatewayRunner.class);
    
    private final HermesConfig config;
    private final List<GatewayServer.PlatformAdapter> adapters;
    private volatile boolean running;
    private DashboardServer dashboardServer;
    
    public GatewayRunner(HermesConfig config) {
        this.config = config;
        this.adapters = new ArrayList<>();
        this.running = false;
    }
    
    /**
     * Run gateway in foreground.
     */
    public void runForeground() {
        logger.info("Starting Hermes Gateway...");
        
        try {
            // Start Dashboard server
            startDashboard();
            
            initializeAdapters();
            startAdapters();
            
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
            stopAdapters();
            stopDashboard();
            running = false;
        }
        
        logger.info("Gateway stopped");
    }

    /**
     * Start the dashboard server.
     */
    private void startDashboard() {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("HERMES_DASHBOARD_PORT", "9119"));
            String host = System.getenv().getOrDefault("HERMES_DASHBOARD_HOST", "127.0.0.1");
            
            dashboardServer = new DashboardServer(port, host, config);
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
    
    /**
     * Start gateway as service (background).
     */
    public void startService() {
        // TODO: Implement service mode with PID file
        logger.info("Starting gateway service...");
        System.out.println("Gateway service started");
    }
    
    /**
     * Stop gateway service.
     */
    public void stopService() {
        // TODO: Implement service stop
        logger.info("Stopping gateway service...");
        System.out.println("Gateway service stopped");
    }
    
    /**
     * Show gateway status.
     */
    public void showStatus() {
        System.out.println("Gateway Status:");
        System.out.println("  Running: " + running);
        System.out.println("  Adapters: " + adapters.size());
        for (GatewayServer.PlatformAdapter adapter : adapters) {
            System.out.println("    - " + adapter.getPlatformName() + ": connected");
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
        for (GatewayServer.PlatformAdapter adapter : adapters) {
            try {
                logger.info("Starting {} adapter...", adapter.getPlatformName());
                // Adapters are webhook-based, no explicit start needed
            } catch (Exception e) {
                logger.error("Failed to start {} adapter: {}", adapter.getPlatformName(), e.getMessage());
            }
        }
    }
    
    /**
     * Stop all adapters.
     */
    private void stopAdapters() {
        for (GatewayServer.PlatformAdapter adapter : adapters) {
            try {
                logger.info("Stopping {} adapter...", adapter.getPlatformName());
                // Adapters are webhook-based, no explicit stop needed
            } catch (Exception e) {
                logger.error("Error stopping {} adapter: {}", adapter.getPlatformName(), e.getMessage());
            }
        }
        
        // Stop dashboard server
        if (dashboardServer != null) {
            logger.info("Stopping dashboard server...");
            dashboardServer.stop();
        }
    }
    
    /**
     * Check if a platform is enabled in config.
     */
    private boolean isPlatformEnabled(String platform) {
        // TODO: Check config for enabled platforms
        return true;
    }
}
