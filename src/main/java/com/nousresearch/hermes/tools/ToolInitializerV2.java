package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.tools.impl.AgentDelegateTool;
import com.nousresearch.hermes.tools.impl.AskUserTool;
import com.nousresearch.hermes.tools.impl.CronjobTool;
import com.nousresearch.hermes.tools.impl.HomeAssistantTool;
import com.nousresearch.hermes.tools.impl.ImageGenerationTool;
import com.nousresearch.hermes.tools.impl.MCPTool;
import com.nousresearch.hermes.tools.impl.OrgNativeTools;
import com.nousresearch.hermes.tools.impl.RLTrainingTool;
import com.nousresearch.hermes.tools.impl.SubAgentTool;
import com.nousresearch.hermes.tools.impl.TTSTool;
import com.nousresearch.hermes.tools.impl.VisionTool;
import com.nousresearch.hermes.tools.impl.browser.BrowserToolV2;
import com.nousresearch.hermes.tools.impl.browser.WebSearchToolV2;
import com.nousresearch.hermes.tools.impl.file.CodeTool;
import com.nousresearch.hermes.tools.impl.file.FileTool;
import com.nousresearch.hermes.tools.impl.file.GitTool;
import com.nousresearch.hermes.tools.impl.file.TerminalTool;
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
        AgentDelegateTool.register(registry);
        
        // RL Training
        new RLTrainingTool().register(registry);
        
        // Org-native: 让 Agent 感知组织（找队友、委派、查知识库、升级）
        OrgNativeTools.register(registry);
        AskUserTool.register(registry);
        
        logger.info("All tools initialized");
    }
}
