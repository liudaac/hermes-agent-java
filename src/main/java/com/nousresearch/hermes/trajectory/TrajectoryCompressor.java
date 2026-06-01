package com.nousresearch.hermes.trajectory;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹压缩器
 * 
 * 功能：
 * - 合并连续重复消息
 * - 删除系统提示等低价值内容
 * - 提取关键决策点
 * - 估算 Token 节省
 */
public class TrajectoryCompressor {
    
    private static final Logger logger = LoggerFactory.getLogger(TrajectoryCompressor.class);
    
    // 最大保留消息数
    private static final int MAX_MESSAGES = 20;
    
    // 重复消息合并阈值（字符数差异<10%视为重复）
    private static final double SIMILARITY_THRESHOLD = 0.9;
    
    /**
     * 压缩轨迹
     * @param entry 原始轨迹
     * @return 压缩后的轨迹
     */
    public TrajectoryEntry compress(TrajectoryEntry entry) {
        if (entry.isCompressed() || entry.getConversations() == null) {
            return entry;
        }
        
        List<ModelMessage> original = entry.getConversations();
        int originalTokens = estimateTokens(original);
        
        // 执行压缩步骤
        List<ModelMessage> compressed = new ArrayList<>();
        
        // 1. 过滤系统消息（通常价值低）
        List<ModelMessage> filtered = filterSystemMessages(original);
        
        // 2. 合并重复消息
        List<ModelMessage> merged = mergeDuplicates(filtered);
        
        // 3. 截断过长消息
        List<ModelMessage> truncated = truncateMessages(merged);
        
        // 4. 保留最后 N 条（最近的最有价值）
        if (truncated.size() > MAX_MESSAGES) {
            compressed = truncated.subList(truncated.size() - MAX_MESSAGES, truncated.size());
        } else {
            compressed = truncated;
        }
        
        // 生成摘要
        String summary = generateSummary(original, compressed);
        
        // 更新 entry
        entry.setConversations(compressed);
        entry.setCompressed(true);
        entry.setOriginalTokenCount(originalTokens);
        entry.setCompressedTokenCount(estimateTokens(compressed));
        entry.setCompressionSummary(summary);
        
        logger.info("Compressed trajectory {}: {} messages -> {} messages, saved ~{} tokens",
            entry.getId(),
            original.size(),
            compressed.size(),
            originalTokens - entry.getCompressedTokenCount()
        );
        
        return entry;
    }
    
    /**
     * 估算 Token 数（粗略估计：1 token ~ 4 chars）
     */
    private int estimateTokens(List<ModelMessage> messages) {
        if (messages == null) return 0;
        int chars = messages.stream()
            .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
            .sum();
        return chars / 4;
    }
    
    /**
     * 过滤系统消息（保留第一条）
     */
    private List<ModelMessage> filterSystemMessages(List<ModelMessage> messages) {
        List<ModelMessage> result = new ArrayList<>();
        boolean firstSystem = true;
        
        for (ModelMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                if (firstSystem) {
                    result.add(msg);
                    firstSystem = false;
                }
                // 跳过后续系统消息
            } else {
                result.add(msg);
            }
        }
        
        return result;
    }
    
    /**
     * 合并重复消息
     */
    private List<ModelMessage> mergeDuplicates(List<ModelMessage> messages) {
        if (messages.size() < 2) return messages;
        
        List<ModelMessage> result = new ArrayList<>();
        ModelMessage last = messages.get(0);
        result.add(last);
        
        for (int i = 1; i < messages.size(); i++) {
            ModelMessage current = messages.get(i);
            
            if (isSimilar(last, current)) {
                // 合并：保留更长的那条
                if (current.getContent().length() > last.getContent().length()) {
                    result.set(result.size() - 1, current);
                    last = current;
                }
            } else {
                result.add(current);
                last = current;
            }
        }
        
        return result;
    }
    
    /**
     * 截断过长消息（保留前500字符+最后100字符）
     */
    private List<ModelMessage> truncateMessages(List<ModelMessage> messages) {
        List<ModelMessage> result = new ArrayList<>();
        
        for (ModelMessage msg : messages) {
            String content = msg.getContent();
            if (content != null && content.length() > 600) {
                String truncated = content.substring(0, 500) + "\n... [truncated " + 
                    (content.length() - 600) + " chars] ...\n" + 
                    content.substring(content.length() - 100);
                msg.setContent(truncated);
            }
            result.add(msg);
        }
        
        return result;
    }
    
    /**
     * 判断两条消息是否相似
     */
    private boolean isSimilar(ModelMessage a, ModelMessage b) {
        if (!a.getRole().equals(b.getRole())) return false;
        
        String ca = a.getContent();
        String cb = b.getContent();
        
        if (ca == null || cb == null) return false;
        
        // 短消息直接比较
        if (ca.length() < 50) {
            return ca.equals(cb);
        }
        
        // 长消息：检查前100字符相似度
        int len = Math.min(100, Math.min(ca.length(), cb.length()));
        int matches = 0;
        for (int i = 0; i < len; i++) {
            if (ca.charAt(i) == cb.charAt(i)) matches++;
        }
        
        return (double) matches / len > SIMILARITY_THRESHOLD;
    }
    
    /**
     * 生成压缩摘要
     */
    private String generateSummary(List<ModelMessage> original, List<ModelMessage> compressed) {
        int saved = original.size() - compressed.size();
        double ratio = original.size() > 0 ? (double) saved / original.size() * 100 : 0;
        
        return String.format("Merged %d duplicates, filtered system msgs, retained last %d messages (%.0f%% reduction)",
            saved,
            compressed.size(),
            ratio
        );
    }
}
