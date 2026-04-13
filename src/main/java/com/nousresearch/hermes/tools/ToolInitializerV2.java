package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.tools.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced tool initializer with all new tools.
 */
public class ToolInitializerV2 {
    private static final Logger logger = LoggerFactory.getLogger(ToolInitializerV2.class);
    
    public static void initializeAll(ToolRegistry registry, ApprovalSystem approvalSystem) {
        logger.info("Initializing all tools...");
        
        // Core tools
        new FileTool().register(registry);
        new TerminalTool().register(registry);
        
        // Web tools
        new WebSearchToolV2().register(registry);
        new BrowserToolV2().register(registry);
        
        // Code execution
        new CodeTool(approvalSystem).register(registry);
        
        // Git
        new GitTool().register(registry);
        
        // Vision
        new VisionTool().register(registry);
        
        // TTS
        new TTSTool().register(registry);
        
        // Image generation
        new ImageGenerationTool().register(registry);
        
        // Cronjob
        new CronjobTool().register(registry);
        
        // Home Assistant
        new HomeAssistantTool().register(registry);
        
        // MCP
        new MCPTool().register(registry);
        
        // Sub-agents
        new SubAgentTool().register(registry);
        
        // RL Training
        new RLTrainingTool().register(registry);
        
        logger.info("All tools initialized");
    }
}
