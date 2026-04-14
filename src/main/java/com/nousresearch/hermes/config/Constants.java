package com.nousresearch.hermes.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared constants for Hermes Agent Java.
 * Import-safe module with no dependencies.
 */
public final class Constants {

    private Constants() {} // Prevent instantiation

    public static final String VERSION = "0.1.0";
    public static final String DEFAULT_HERMES_HOME = ".hermes";
    
    // API Endpoints
    public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String OPENROUTER_MODELS_URL = OPENROUTER_BASE_URL + "/models";
    public static final String AI_GATEWAY_BASE_URL = "https://ai-gateway.vercel.sh/v1";
    public static final String NOUS_API_BASE_URL = "https://inference-api.nousresearch.com/v1";

    // Default configuration values
    public static final int DEFAULT_MAX_ITERATIONS = 90;
    public static final int DEFAULT_SUBAGENT_MAX_ITERATIONS = 50;
    public static final int DEFAULT_CONTEXT_LIMIT = 128000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;
    
    // Tool execution
    public static final int MAX_TOOL_WORKERS = 8;
    public static final long DEFAULT_RESULT_SIZE_CHARS = 50000;

    // Agent identity
    public static final String DEFAULT_AGENT_IDENTITY = 
        "You are Hermes Agent, an intelligent AI assistant created by Nous Research. " +
        "You are helpful, knowledgeable, and direct. You assist users with a wide " +
        "range of tasks including answering questions, writing and editing code, " +
        "analyzing information, creative work, and executing actions via your tools. " +
        "You communicate clearly, admit uncertainty when appropriate, and prioritize " +
        "being genuinely useful over being verbose. " +
        "IMPORTANT: You CAN and SHOULD use browser tools (browser_navigate, browser_click, etc.) " +
        "when the user asks you to open a webpage or interact with a website. " +
        "Do NOT say you cannot open browsers - you have browser tools available and should use them.";

    public static final String MEMORY_GUIDANCE = 
        "You have persistent memory across sessions. Save durable facts using the memory " +
        "tool: user preferences, environment details, tool quirks, and stable conventions. " +
        "Memory is injected into every turn, so keep it compact and focused on facts that " +
        "will still matter later.";

    public static final String TOOL_USE_ENFORCEMENT_GUIDANCE = 
        "You MUST use your tools to take action — do not describe what you would do " +
        "or plan to do without actually doing it. When you say you will perform an " +
        "action, you MUST immediately make the corresponding tool call in the same " +
        "response.";

    /**
     * Get the Hermes home directory.
     */
    public static Path getHermesHome() {
        String envHome = System.getenv("HERMES_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            return Paths.get(envHome);
        }
        return Paths.get(System.getProperty("user.home"), DEFAULT_HERMES_HOME);
    }

    /**
     * Check if running in Termux (Android).
     */
    public static boolean isTermux() {
        String prefix = System.getenv("PREFIX");
        return System.getenv("TERMUX_VERSION") != null ||
               (prefix != null && prefix.contains("com.termux/files/usr"));
    }

    /**
     * Check if running in WSL.
     */
    public static boolean isWSL() {
        try {
            Path versionPath = Paths.get("/proc/version");
            if (versionPath.toFile().exists()) {
                String content = java.nio.file.Files.readString(versionPath);
                return content.toLowerCase().contains("microsoft");
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }
}
