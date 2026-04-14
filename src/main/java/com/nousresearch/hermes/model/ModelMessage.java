package com.nousresearch.hermes.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;

import java.util.ArrayList;
import java.util.List;

/**
 * Message types for LLM API communication.
 * Compatible with OpenAI-style chat completions API.
 */
public class ModelMessage {
    
    private String role;
    private String content;
    private String name;
    
    @JSONField(name = "tool_calls")
    private List<ToolCall> toolCalls;
    
    @JSONField(name = "tool_call_id")
    private String toolCallId;
    
    // For multimodal content
    private List<ContentPart> contentParts;
    
    public ModelMessage() {}
    
    public ModelMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
    
    public static ModelMessage system(String content) {
        return new ModelMessage("system", content);
    }
    
    public static ModelMessage user(String content) {
        return new ModelMessage("user", content);
    }
    
    public static ModelMessage assistant(String content) {
        return new ModelMessage("assistant", content);
    }
    
    public static ModelMessage tool(String content, String toolCallId) {
        ModelMessage msg = new ModelMessage("tool", content);
        msg.toolCallId = toolCallId;
        return msg;
    }
    
    // Getters and setters
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    
    public List<ContentPart> getContentParts() { return contentParts; }
    public void setContentParts(List<ContentPart> contentParts) { this.contentParts = contentParts; }
    
    /**
     * Convert this message to a Fastjson2 JSONObject for API serialization.
     * This ensures proper field naming and null handling.
     */
    public JSONObject toJsonObject() {
        JSONObject json = new JSONObject();
        json.put("role", role);
        if (content != null) {
            json.put("content", content);
        }
        if (name != null) {
            json.put("name", name);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            json.put("tool_calls", toolCalls);
        }
        if (toolCallId != null) {
            json.put("tool_call_id", toolCallId);
        }
        return json;
    }
    
    /**
     * Convert a list of ModelMessages to a list of JSONObjects.
     */
    public static List<JSONObject> toJsonObjectList(List<ModelMessage> messages) {
        List<JSONObject> result = new ArrayList<>();
        for (ModelMessage msg : messages) {
            result.add(msg.toJsonObject());
        }
        return result;
    }
    
    /**
     * Content part for multimodal messages.
     */
    public static class ContentPart {
        private String type;
        private String text;
        private ImageUrl imageUrl;
        
        public ContentPart() {}
        
        public static ContentPart text(String text) {
            ContentPart part = new ContentPart();
            part.type = "text";
            part.text = text;
            return part;
        }
        
        public static ContentPart image(String url) {
            ContentPart part = new ContentPart();
            part.type = "image_url";
            part.imageUrl = new ImageUrl(url);
            return part;
        }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        
        public ImageUrl getImageUrl() { return imageUrl; }
        public void setImageUrl(ImageUrl imageUrl) { this.imageUrl = imageUrl; }
    }
    
    /**
     * Image URL structure.
     */
    public static class ImageUrl {
        private String url;
        private String detail;
        
        public ImageUrl() {}
        
        public ImageUrl(String url) {
            this.url = url;
            this.detail = "auto";
        }
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
    }
}
