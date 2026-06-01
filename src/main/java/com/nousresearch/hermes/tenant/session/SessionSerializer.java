package com.nousresearch.hermes.tenant.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话序列化器接口
 * 
 * 支持将 Session 对象序列化为字节数组，以便持久化到数据库或文件系统
 */
public interface SessionSerializer {
    
    /**
     * 序列化会话
     * @param sessionData 会话数据
     * @return 序列化后的字节数组
     */
    byte[] serialize(SessionData sessionData);
    
    /**
     * 反序列化会话
     * @param bytes 序列化后的字节数组
     * @return 会话数据
     */
    SessionData deserialize(byte[] bytes);
    
    /**
     * 会话数据对象
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionData(
        String sessionId,
        String tenantId,
        String nodeId,
        Instant createdAt,
        Instant lastActivity,
        Map<String, Object> metadata,
        boolean active,
        List<ConversationMessage> messages
    ) {
        /**
         * 创建会话数据
         */
        public static SessionData create(String sessionId, String tenantId, String nodeId) {
            return new SessionData(
                sessionId,
                tenantId,
                nodeId,
                Instant.now(),
                Instant.now(),
                Map.of(),
                true,
                List.of()
            );
        }
        
        /**
         * 创建带消息的会话数据
         */
        public static SessionData withMessages(String sessionId, String tenantId, String nodeId, 
                                                List<ConversationMessage> messages) {
            return new SessionData(
                sessionId,
                tenantId,
                nodeId,
                Instant.now(),
                Instant.now(),
                Map.of(),
                true,
                messages
            );
        }
        
        /**
         * 创建带元数据的会话数据
         */
        public static SessionData withMetadata(String sessionId, String tenantId, String nodeId,
                                                Map<String, Object> metadata) {
            return new SessionData(
                sessionId,
                tenantId,
                nodeId,
                Instant.now(),
                Instant.now(),
                metadata,
                true,
                List.of()
            );
        }
        
        /**
         * 添加消息
         */
        public SessionData addMessage(ConversationMessage message) {
            List<ConversationMessage> newMessages = new java.util.ArrayList<>(this.messages);
            newMessages.add(message);
            return new SessionData(
                sessionId, tenantId, nodeId, createdAt, Instant.now(), 
                metadata, active, newMessages
            );
        }
        
        /**
         * 获取消息数量
         */
        public int messageCount() {
            return messages != null ? messages.size() : 0;
        }
    }
    
    /**
     * 对话消息
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConversationMessage(
        String role,        // "user", "assistant", "system", "tool"
        String content,
        Instant timestamp,
        String toolName,    // 如果是 tool 调用
        String toolInput,   // 工具输入
        String toolOutput   // 工具输出
    ) {
        /**
         * 创建用户消息
         */
        public static ConversationMessage user(String content) {
            return new ConversationMessage("user", content, Instant.now(), null, null, null);
        }
        
        /**
         * 创建助手消息
         */
        public static ConversationMessage assistant(String content) {
            return new ConversationMessage("assistant", content, Instant.now(), null, null, null);
        }
        
        /**
         * 创建系统消息
         */
        public static ConversationMessage system(String content) {
            return new ConversationMessage("system", content, Instant.now(), null, null, null);
        }
        
        /**
         * 创建工具调用请求
         */
        public static ConversationMessage toolRequest(String toolName, String input) {
            return new ConversationMessage("tool", null, Instant.now(), toolName, input, null);
        }
        
        /**
         * 创建工具调用响应
         */
        public static ConversationMessage toolResponse(String toolName, String output) {
            return new ConversationMessage("tool", null, Instant.now(), toolName, null, output);
        }
        
        /**
         * 判断是否为用户消息
         */
        public boolean isUser() {
            return "user".equals(role);
        }
        
        /**
         * 判断是否为助手消息
         */
        public boolean isAssistant() {
            return "assistant".equals(role);
        }
        
        /**
         * 判断是否为工具消息
         */
        public boolean isTool() {
            return "tool".equals(role);
        }
    }
}
