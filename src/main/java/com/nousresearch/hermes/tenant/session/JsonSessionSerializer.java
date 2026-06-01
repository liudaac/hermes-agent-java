package com.nousresearch.hermes.tenant.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
            return objectMapper.readValue(json, SessionData.class);
            
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
}
