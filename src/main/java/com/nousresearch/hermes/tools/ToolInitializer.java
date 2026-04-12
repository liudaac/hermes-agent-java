package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.tools.impl.BrowserTool;
import com.nousresearch.hermes.tools.impl.CodeTool;
import com.nousresearch.hermes.tools.impl.FileTool;
import com.nousresearch.hermes.tools.impl.GitTool;
import com.nousresearch.hermes.tools.impl.MemoryTool;
import com.nousresearch.hermes.tools.impl.SkillTool;
import com.nousresearch.hermes.tools.impl.SubAgentTool;
import com.nousresearch.hermes.tools.impl.TerminalTool;
import com.nousresearch.hermes.tools.impl.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Initializes and registers all built-in tools.
 */
public class ToolInitializer {
    private static final Logger logger = LoggerFactory.getLogger(ToolInitializer.class);
    
    /**
     * Register all built-in tools.
     */
    public static void initialize() {
        logger.info("Initializing tools...");
        
        ToolRegistry registry = ToolRegistry.getInstance();
        
        // Register web search tools
        try {
            WebSearchTool.register(registry);
            logger.debug("Registered web search tools");
        } catch (Exception e) {
            logger.warn("Failed to register web search tools: {}", e.getMessage());
        }
        
        // Register terminal tools
        try {
            TerminalTool.register(registry);
            logger.debug("Registered terminal tools");
        } catch (Exception e) {
            logger.warn("Failed to register terminal tools: {}", e.getMessage());
        }
        
        // Register file tools
        try {
            FileTool.register(registry);
            logger.debug("Registered file tools");
        } catch (Exception e) {
            logger.warn("Failed to register file tools: {}", e.getMessage());
        }
        
        // Register memory tools
        try {
            MemoryTool.register(registry);
            logger.debug("Registered memory tools");
        } catch (Exception e) {
            logger.warn("Failed to register memory tools: {}", e.getMessage());
        }
        
        // Register browser tools
        try {
            BrowserTool.register(registry);
            logger.debug("Registered browser tools");
        } catch (Exception e) {
            logger.warn("Failed to register browser tools: {}", e.getMessage());
        }
        
        // Register skill tools
        try {
            SkillTool.register(registry);
            logger.debug("Registered skill tools");
        } catch (Exception e) {
            logger.warn("Failed to register skill tools: {}", e.getMessage());
        }
        
        // Register sub-agent tools
        try {
            SubAgentTool.register(registry);
            logger.debug("Registered sub-agent tools");
        } catch (Exception e) {
            logger.warn("Failed to register sub-agent tools: {}", e.getMessage());
        }
        
        // Register code execution tools
        try {
            CodeTool.register(registry);
            logger.debug("Registered code execution tools");
        } catch (Exception e) {
            logger.warn("Failed to register code tools: {}", e.getMessage());
        }
        
        // Register Git tools
        try {
            GitTool.register(registry);
            logger.debug("Registered Git tools");
        } catch (Exception e) {
            logger.warn("Failed to register Git tools: {}", e.getMessage());
        }
        
        // Log summary
        List<String> tools = registry.getAllToolNames();
        logger.info("Registered {} tools: {}", tools.size(), tools);
    }
}
