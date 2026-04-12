package com.nousresearch.hermes.gateway;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.gateway.platforms.DiscordAdapter;
import com.nousresearch.hermes.gateway.platforms.FeishuAdapter;
import com.nousresearch.hermes.gateway.platforms.PlatformAdapter;
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
    private final List<PlatformAdapter> adapters;
    private volatile boolean running;
    
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
            running = false;
        }
        
        logger.info("Gateway stopped");
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
        for (PlatformAdapter adapter : adapters) {
            System.out.println("    - " + adapter.getName() + ": " + 
                (adapter.isConnected() ? "connected" : "disconnected"));
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
        for (PlatformAdapter adapter : adapters) {
            try {
                logger.info("Starting {} adapter...", adapter.getName());
                adapter.start();
            } catch (Exception e) {
                logger.error("Failed to start {} adapter: {}", adapter.getName(), e.getMessage());
            }
        }
    }
    
    /**
     * Stop all adapters.
     */
    private void stopAdapters() {
        for (PlatformAdapter adapter : adapters) {
            try {
                logger.info("Stopping {} adapter...", adapter.getName());
                adapter.stop();
            } catch (Exception e) {
                logger.error("Error stopping {} adapter: {}", adapter.getName(), e.getMessage());
            }
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
