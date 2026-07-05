package com.nousresearch.hermes.tenant.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JSON 格式的会话序列化器
 * 
 * 使用 Jackson 进行 JSON 序列化，支持：
 * - 时间戳自动序列化
 * - 循环引用安全
 * - 压缩选项
 */
public class JsonSessionSerializer implements SessionSerializer {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonSessionSerializer.class);
    
    private final ObjectMapper objectMapper;
    private final boolean prettyPrint;
    private final boolean compress;
    
    /**
     * 创建标准 JSON 序列化器
     */
    public JsonSessionSerializer() {
        this(false, false);
    }
    
    /**
     * 创建 JSON 序列化器
     * @param prettyPrint 是否美化输出
     * @param compress 是否压缩（移除空格）
     */
    public JsonSessionSerializer(boolean prettyPrint, boolean compress) {
        this.prettyPrint = prettyPrint;
        this.compress = compress;
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        
        if (prettyPrint) {
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
    }
    
    @Override
    public byte[] serialize(SessionData sessionData) {
        if (sessionData == null) {
            return new byte[0];
        }
        
        try {
            String json = objectMapper.writeValueAsString(sessionData);
            
            if (compress) {
                // 移除所有空格和换行以减小体积
                json = json.replaceAll("\\s+", "");
            }
            
            return json.getBytes(StandardCharsets.UTF_8);
            
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize session: {}", sessionData.sessionId(), e);
            return new byte[0];
        }
    }
    
    @Override
    public SessionData deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            SessionData data = objectMapper.readValue(json, SessionData.class);
            // S1-2: 反序列化后 sanitize，拒绝加载 api_key 等敏感字段
            return sanitizeSessionData(data);
            
        } catch (IOException e) {
            logger.error("Failed to deserialize session: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 序列化会话为字符串
     */
    public String serializeToString(SessionData sessionData) {
        byte[] bytes = serialize(sessionData);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    /**
     * 从字符串反序列化会话
     */
    public SessionData deserializeFromString(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        try {
            return objectMapper.readValue(json, SessionData.class);
        } catch (IOException e) {
            logger.error("Failed to deserialize session from string: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取 ObjectMapper 以便自定义配置
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
    
    /**
     * 创建压缩型序列化器（适合数据库存储）
     */
    public static SessionSerializer compressed() {
        return new JsonSessionSerializer(false, true);
    }
    
    /**
     * 创建美化输出序列化器（适合调试）
     */
    public static SessionSerializer prettyPrinted() {
        return new JsonSessionSerializer(true, false);
    }

    // ============ S1-2: Model Override 安全存取 ============

    /**
     * S1-2: 敏感字段黑名单 — 绝不允许从持久化数据中加载。
     */
    private static final java.util.Set<String> FORBIDDEN_OVERRIDE_KEYS = java.util.Set.of(
        "api_key", "apikey", "api-key",
        "secret", "password", "token",
        "authorization", "auth_token"
    );

    /**
     * S1-2: 反序列化后对 SessionData 做 sanitize。
     *
     * <p>检查 metadata.model_override，如果包含 api_key 等敏感字段，
     * 移除这些字段后重建 SessionData。如果 model_override 本身为空则跳过。</p>
     *
     * @param data 原始反序列化的 SessionData
     * @return sanitize 后的 SessionData
     */
    SessionData sanitizeSessionData(SessionData data) {
        if (data == null) return null;
        if (data.metadata() == null || data.metadata().isEmpty()) return data;

        Object overrideRaw = data.metadata().get("model_override");
        if (!(overrideRaw instanceof Map<?, ?>)) return data;

        @SuppressWarnings("unchecked")
        Map<String, Object> overrideMap = new java.util.LinkedHashMap<>(
            (Map<String, Object>) overrideRaw);

        // 移除敏感字段
        boolean hadForbidden = false;
        for (String key : FORBIDDEN_OVERRIDE_KEYS) {
            if (overrideMap.containsKey(key)) {
                logger.warn("S1-2: Stripped forbidden field '{}' from model_override in session {}",
                    key, data.sessionId());
                overrideMap.remove(key);
                hadForbidden = true;
            }
        }

        if (!hadForbidden) return data; // 无需重建

        // 重建 metadata
        Map<String, Object> newMetadata = new java.util.LinkedHashMap<>(data.metadata());
        newMetadata.put("model_override", overrideMap);

        return new SessionData(
            data.sessionId(), data.tenantId(), data.nodeId(),
            data.createdAt(), data.lastActivity(), newMetadata,
            data.active(), data.messages()
        );
    }

    /**
     * S1-2: 从 SessionData 提取 ModelOverride（已 sanitize）。
     */
    public static ModelOverride getModelOverride(SessionData data) {
        if (data == null || data.metadata() == null) return null;
        Object raw = data.metadata().get("model_override");
        if (!(raw instanceof Map<?, ?> map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> overrideMap = (Map<String, Object>) map;
        // 再次 sanitize（防御性）
        for (String key : FORBIDDEN_OVERRIDE_KEYS) {
            overrideMap.remove(key);
        }
        return ModelOverride.fromMap(overrideMap);
    }

    /**
     * S1-2: 将 ModelOverride 写入 SessionData.metadata。
     * 返回新的 SessionData（record 不可变）。
     */
    public static SessionData setModelOverride(SessionData data, ModelOverride override) {
        if (data == null) return null;
        Map<String, Object> newMetadata = new java.util.LinkedHashMap<>(
            data.metadata() != null ? data.metadata() : Map.of());

        if (override != null) {
            newMetadata.put("model_override", override.toMap());
        } else {
            newMetadata.remove("model_override");
        }

        return new SessionData(
            data.sessionId(), data.tenantId(), data.nodeId(),
            data.createdAt(), data.lastActivity(), newMetadata,
            data.active(), data.messages()
        );
    }

    /**
     * S1-2: 清除 SessionData 中的 ModelOverride（对应 /new 重置）。
     */
    public static SessionData clearModelOverride(SessionData data) {
        return setModelOverride(data, null);
    }
}
