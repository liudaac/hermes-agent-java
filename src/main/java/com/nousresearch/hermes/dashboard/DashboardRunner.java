package com.nousresearch.hermes.dashboard;

import com.nousresearch.hermes.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runner for the Hermes Dashboard Web UI.
 * Provides a standalone entry point for starting the dashboard server.
 */
public class DashboardRunner {
    private static final Logger logger = LoggerFactory.getLogger(DashboardRunner.class);

    public static final int DEFAULT_PORT = 9119;
    public static final String DEFAULT_HOST = "127.0.0.1";

    private DashboardServer server;
    private Thread serverThread;

    /**
     * Start the dashboard server.
     */
    public void start(int port, String host) {
        logger.info("Starting Hermes Dashboard on http://{}:{}", host, port);

        HermesConfig config;
        try {
            config = HermesConfig.load();
        } catch (Exception e) {
            logger.warn("Could not load config, using defaults: {}", e.getMessage());
            config = new HermesConfig();  // Uses default config when file doesn't exist
        }
        server = new DashboardServer(port, host, config);

        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                logger.error("Dashboard server error: {}", e.getMessage(), e);
            }
        }, "dashboard-server");

        serverThread.setDaemon(false);
        serverThread.start();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down dashboard server...");
            stop();
        }));

        // Wait for server to start
        try {
            Thread.sleep(1000);
            logger.info("Dashboard server ready at http://{}:{}", host, port);
            logger.info("Session token: {}", maskToken(server.getSessionToken()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stop the dashboard server.
     */
    public void stop() {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    /**
     * Check if the server is running.
     */
    public boolean isRunning() {
        return serverThread != null && serverThread.isAlive();
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 10) return "****";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    /**
     * Main entry point for standalone dashboard.
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String host = DEFAULT_HOST;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") || args[i].equals("-p")) {
                if (i + 1 < args.length) {
                    port = Integer.parseInt(args[i + 1]);
                    i++;
                }
            } else if (args[i].equals("--host") || args[i].equals("-h")) {
                if (i + 1 < args.length) {
                    host = args[i + 1];
                    i++;
                }
            }
        }

        // Check for environment variables
        String envPort = System.getenv("HERMES_DASHBOARD_PORT");
        if (envPort != null) {
            port = Integer.parseInt(envPort);
        }

        String envHost = System.getenv("HERMES_DASHBOARD_HOST");
        if (envHost != null) {
            host = envHost;
        }

        DashboardRunner runner = new DashboardRunner();
        runner.start(port, host);

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
