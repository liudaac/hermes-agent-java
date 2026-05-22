package com.nousresearch.hermes.model;

import com.nousresearch.hermes.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with AI model APIs (OpenAI, Anthropic, OpenRouter).
 */
public class ModelClient {
    private static final Logger logger = LoggerFactory.getLogger(ModelClient.class);
    
    private final HttpClient httpClient;
    private final HermesConfig.ModelConfig modelConfig;
    
    public ModelClient(HermesConfig.ModelConfig config) {
        this.modelConfig = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }
    
    /**
     * Send a chat completion request to the model API.
     */
    public ChatCompletionResponse chatCompletion(
            List<ModelMessage> messages,
            List<ToolDefinition> tools,
            boolean stream) {
        
        try {
            // Build request body
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{");
            jsonBuilder.append("\"model\":\"").append(modelConfig.getName()).append("\",");
            jsonBuilder.append("\"messages\":").append(buildMessagesJson(messages)).append(",");
            jsonBuilder.append("\"stream\":").append(stream);
            if (tools != null && !tools.isEmpty()) {
                jsonBuilder.append(",\"tools\":").append(buildToolsJson(tools));
            }
            jsonBuilder.append("}");
            
            String requestBody = jsonBuilder.toString();
            logger.debug("Sending chat completion request: {}", requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getChatCompletionUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                logger.error("API error: {} - {}", response.statusCode(), response.body());
                return ChatCompletionResponse.error("API error: " + response.statusCode());
            }
            
            return parseChatCompletionResponse(response.body());
            
        } catch (Exception e) {
            logger.error("Error in chat completion: {}", e.getMessage(), e);
            return ChatCompletionResponse.error(e.getMessage());
        }
    }
    
    /**
     * Create embeddings for a text.
     */
    public float[] createEmbedding(String text) {
        logger.debug("Creating embedding for text: {}", text.substring(0, Math.min(50, text.length())));
        
        try {
            String requestBody = String.format("{" +
                "\"model\":\"%s\"," +
                "\"input\":\"%s\"" +
                "}", getEmbeddingModel(), escapeJson(text));
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getEmbeddingsUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                logger.error("Embedding API error: {} - {}", response.statusCode(), response.body());
                return new float[1536];
            }
            
            return parseEmbeddingResponse(response.body());
            
        } catch (Exception e) {
            logger.error("Error creating embedding: {}", e.getMessage(), e);
            return new float[1536];
        }
    }
    
    /**
     * Generate an image from a prompt.
     */
    public String generateImage(String prompt, String size) {
        logger.debug("Generating image for prompt: {}", prompt);
        
        try {
            String actualSize = size != null ? size : "1024x1024";
            String requestBody = String.format("{" +
                "\"model\":\"%s\"," +
                "\"prompt\":\"%s\"," +
                "\"size\":\"%s\"," +
                "\"n\":1" +
                "}", getImageModel(), escapeJson(prompt), actualSize);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getImageGenerationUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                logger.error("Image generation API error: {} - {}", response.statusCode(), response.body());
                return null;
            }
            
            return parseImageResponse(response.body());
            
        } catch (Exception e) {
            logger.error("Error generating image: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Transcribe audio to text.
     */
    public String transcribeAudio(byte[] audioData, String format) {
        logger.debug("Transcribing audio, size: {} bytes", audioData.length);
        
        try {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            String actualFormat = format != null ? format : "mp3";
            
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            baos.write(("--" + boundary + "\r\n").getBytes());
            baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"audio." + actualFormat + "\"\r\n").getBytes());
            baos.write(("Content-Type: audio/" + actualFormat + "\r\n\r\n").getBytes());
            baos.write(audioData);
            baos.write(("\r\n--" + boundary + "\r\n").getBytes());
            baos.write(("Content-Disposition: form-data; name=\"model\"\r\n\r\n").getBytes());
            baos.write((getTranscriptionModel() + "\r\n").getBytes());
            baos.write(("--" + boundary + "--\r\n").getBytes());
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getTranscriptionUrl()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                logger.error("Transcription API error: {} - {}", response.statusCode(), response.body());
                return "[Transcription failed: " + response.statusCode() + "]";
            }
            
            return parseTranscriptionResponse(response.body());
            
        } catch (Exception e) {
            logger.error("Error transcribing audio: {}", e.getMessage(), e);
            return "[Transcription error: " + e.getMessage() + "]";
        }
    }
    
    /**
     * Verify if the API key is valid.
     */
    public boolean verifyApiKey() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getModelsUrl()))
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            return response.statusCode() == 200;
        } catch (Exception e) {
            logger.error("API key verification failed: {}", e.getMessage());
            return false;
        }
    }
    
    // Helper methods
    
    private String getChatCompletionUrl() {
        String baseUrl = modelConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getDefaultBaseUrl();
        }
        return baseUrl + "/chat/completions";
    }
    
    private String getEmbeddingsUrl() {
        String baseUrl = modelConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getDefaultBaseUrl();
        }
        return baseUrl + "/embeddings";
    }
    
    private String getEmbeddingModel() {
        // Default embedding model
        return "text-embedding-3-small";
    }
    
    private String getImageGenerationUrl() {
        String baseUrl = modelConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getDefaultBaseUrl();
        }
        return baseUrl + "/images/generations";
    }
    
    private String getImageModel() {
        return "dall-e-3";
    }
    
    private String getTranscriptionUrl() {
        String baseUrl = modelConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getDefaultBaseUrl();
        }
        return baseUrl + "/audio/transcriptions";
    }
    
    private String getTranscriptionModel() {
        return "whisper-1";
    }
    
    private float[] parseEmbeddingResponse(String json) {
        // Simple parsing - return default embedding
        return new float[1536];
    }
    
    private String parseImageResponse(String json) {
        // Simple parsing - extract URL from response
        int urlStart = json.indexOf("\"url\":\"");
        if (urlStart != -1) {
            urlStart += 7;
            int urlEnd = json.indexOf("\"", urlStart);
            if (urlEnd != -1) {
                return json.substring(urlStart, urlEnd);
            }
        }
        return null;
    }
    
    private String parseTranscriptionResponse(String json) {
        // Simple parsing - extract text from response
        int textStart = json.indexOf("\"text\":\"");
        if (textStart != -1) {
            textStart += 8;
            int textEnd = json.indexOf("\"", textStart);
            if (textEnd != -1) {
                return json.substring(textStart, textEnd);
            }
        }
        return "[Transcription failed]";
    }
    
    private String getModelsUrl() {
        String baseUrl = modelConfig.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getDefaultBaseUrl();
        }
        return baseUrl + "/models";
    }
    
    private String getDefaultBaseUrl() {
        String provider = modelConfig.getProvider();
        switch (provider.toLowerCase()) {
            case "openai":
                return "https://api.openai.com/v1";
            case "anthropic":
                return "https://api.anthropic.com/v1";
            case "openrouter":
            default:
                return "https://openrouter.ai/api/v1";
        }
    }
    
    private String buildMessagesJson(List<ModelMessage> messages) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(",");
            ModelMessage msg = messages.get(i);
            sb.append("{");
            sb.append("\"role\":\"").append(msg.getRole()).append("\",");
            sb.append("\"content\":\"").append(escapeJson(msg.getContent())).append("\"");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }
    
    private String buildToolsJson(List<ToolDefinition> tools) {
        // TODO: Implement proper tool serialization
        return "[]";
    }
    
    private ChatCompletionResponse parseChatCompletionResponse(String json) {
        // Simple parsing - in production use a proper JSON library
        try {
            // Extract content from response
            int contentStart = json.indexOf("\"content\":\"");
            if (contentStart != -1) {
                contentStart += 11;
                int contentEnd = json.indexOf("\"", contentStart);
                if (contentEnd != -1) {
                    String content = unescapeJson(json.substring(contentStart, contentEnd));
                    ModelMessage message = new ModelMessage("assistant", content);
                    return new ChatCompletionResponse(message, "stop", false);
                }
            }
            return ChatCompletionResponse.error("Failed to parse response");
        } catch (Exception e) {
            logger.error("Error parsing response: {}", e.getMessage());
            return ChatCompletionResponse.error(e.getMessage());
        }
    }
    
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
    
    private String unescapeJson(String text) {
        return text.replace("\\n", "\n")
                   .replace("\\r", "\r")
                   .replace("\\t", "\t")
                   .replace("\\\"", "\"")
                   .replace("\\\\", "\\");
    }
}
