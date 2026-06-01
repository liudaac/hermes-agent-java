package com.nousresearch.hermes.trajectory;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识提取器
 * 
 * 从轨迹中提取可复用的知识：
 * - 成功模式：识别导致成功结果的用户-助手交互
 * - 失败教训：从失败轨迹中提取常见错误
 * - 工具使用模式：发现有效的工具调用序列
 * - 记忆线索：提取值得长期保存的信息
 */
public class InsightExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(InsightExtractor.class);
    
    // 工具调用模式
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
        "Using tool:\\s*(\\w+)|tool_call|function_call",
        Pattern.CASE_INSENSITIVE
    );
    
    // 错误指示器
    private static final Pattern[] ERROR_PATTERNS = {
        Pattern.compile("(?i)error|exception|failed|timeout|refused"),
        Pattern.compile("(?i)cannot|unable|did not|could not"),
        Pattern.compile("(?i)sorry.*(?:cannot|don't|can't)")
    };
    
    // 成功指示器
    private static final Pattern[] SUCCESS_PATTERNS = {
        Pattern.compile("(?i)success|completed|done|finished"),
        Pattern.compile("(?i)here (?:is|are) the|I've (?:created|generated|done)"),
        Pattern.compile("(?i)let me know if you need")
    };
    
    /**
     * 从轨迹中提取知识
     * @param entry 轨迹条目
     * @return 提取的知识列表
     */
    public List<String> extract(TrajectoryEntry entry) {
        List<String> insights = new ArrayList<>();
        
        if (entry.getConversations() == null || entry.getConversations().isEmpty()) {
            return insights;
        }
        
        List<ModelMessage> messages = entry.getConversations();
        
        // 1. 提取工具使用模式
        List<String> toolPatterns = extractToolPatterns(messages);
        insights.addAll(toolPatterns);
        
        // 2. 提取成功/失败模式
        if (entry.isCompleted()) {
            insights.addAll(extractSuccessPatterns(messages));
        } else {
            insights.addAll(extractFailureLessons(messages));
        }
        
        // 3. 提取有价值的交互模式
        insights.addAll(extractInteractionPatterns(messages));
        
        // 4. 提取记忆线索
        insights.addAll(extractMemoryHints(messages));
        
        logger.debug("Extracted {} insights from trajectory {}", insights.size(), entry.getId());
        
        return insights;
    }
    
    /**
     * 提取工具使用模式
     */
    private List<String> extractToolPatterns(List<ModelMessage> messages) {
        List<String> patterns = new ArrayList<>();
        List<String> toolsUsed = new ArrayList<>();
        
        for (ModelMessage msg : messages) {
            if (msg.getContent() == null) continue;
            
            Matcher m = TOOL_CALL_PATTERN.matcher(msg.getContent());
            while (m.find()) {
                String tool = m.group(1);
                if (tool != null && !toolsUsed.contains(tool)) {
                    toolsUsed.add(tool);
                }
            }
        }
        
        if (!toolsUsed.isEmpty()) {
            patterns.add("Tools used: " + String.join(" -> ", toolsUsed));
        }
        
        return patterns;
    }
    
    /**
     * 提取成功模式
     */
    private List<String> extractSuccessPatterns(List<ModelMessage> messages) {
        List<String> patterns = new ArrayList<>();
        
        // 查找最后的助手消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            ModelMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                for (Pattern p : SUCCESS_PATTERNS) {
                    if (p.matcher(msg.getContent()).find()) {
                        patterns.add("Success pattern: " + msg.getContent().substring(0, 
                            Math.min(100, msg.getContent().length())));
                        break;
                    }
                }
                break;
            }
        }
        
        return patterns;
    }
    
    /**
     * 提取失败教训
     */
    private List<String> extractFailureLessons(List<ModelMessage> messages) {
        List<String> lessons = new ArrayList<>();
        
        for (ModelMessage msg : messages) {
            if (msg.getContent() == null) continue;
            
            for (Pattern p : ERROR_PATTERNS) {
                if (p.matcher(msg.getContent()).find()) {
                    String lesson = msg.getContent().substring(0, 
                        Math.min(150, msg.getContent().length()));
                    if (!lessons.contains(lesson)) {
                        lessons.add("Failure lesson: " + lesson);
                    }
                    break;
                }
            }
        }
        
        return lessons;
    }
    
    /**
     * 提取交互模式
     */
    private List<String> extractInteractionPatterns(List<ModelMessage> messages) {
        List<String> patterns = new ArrayList<>();
        
        // 计算对话轮数
        int turns = 0;
        String lastRole = null;
        for (ModelMessage msg : messages) {
            if (!msg.getRole().equals(lastRole)) {
                turns++;
                lastRole = msg.getRole();
            }
        }
        
        patterns.add("Interaction pattern: " + turns + " turns, " + messages.size() + " messages");
        
        // 检测多轮迭代模式
        if (turns > 10) {
            patterns.add("Complex task pattern: Required " + turns + " turns to resolve");
        }
        
        return patterns;
    }
    
    /**
     * 提取记忆线索
     */
    private List<String> extractMemoryHints(List<ModelMessage> messages) {
        List<String> hints = new ArrayList<>();
        
        // 提取用户偏好（从 user 消息中）
        for (ModelMessage msg : messages) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent().toLowerCase();
                
                // 检测偏好表达
                if (content.contains("prefer") || content.contains("like") || content.contains("always")) {
                    hints.add("User preference: " + msg.getContent().substring(0,
                        Math.min(100, msg.getContent().length())));
                }
                
                // 检测重要上下文
                if (content.contains("remember") || content.contains("don't forget")) {
                    hints.add("Memory hint: " + msg.getContent().substring(0,
                        Math.min(100, msg.getContent().length())));
                }
            }
        }
        
        return hints;
    }
    
    /**
     * 生成轨迹总结
     */
    public String generateSummary(TrajectoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Trajectory Summary:\n");
        sb.append("  ID: ").append(entry.getId()).append("\n");
        sb.append("  Model: ").append(entry.getModel()).append("\n");
        sb.append("  Completed: ").append(entry.isCompleted()).append("\n");
        sb.append("  Messages: ").append(
            entry.getConversations() != null ? entry.getConversations().size() : 0).append("\n");
        
        if (entry.getOriginalTokenCount() != null) {
            sb.append("  Original tokens: ").append(entry.getOriginalTokenCount()).append("\n");
        }
        if (entry.getCompressedTokenCount() != null) {
            sb.append("  Compressed tokens: ").append(entry.getCompressedTokenCount()).append("\n");
        }
        if (entry.getExtractedInsights() != null) {
            sb.append("  Insights: ").append(entry.getExtractedInsights().size()).append("\n");
        }
        
        return sb.toString();
    }
}
