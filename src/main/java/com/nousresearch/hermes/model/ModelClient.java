package com.nousresearch.hermes.model;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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
        return chatCompletion(messages, tools, stream, null);
    }

    /**
     * Send a chat completion request with optional parameter overrides.
     */
    public ChatCompletionResponse chatCompletion(
            List<ModelMessage> messages,
            List<ToolDefinition> tools,
            boolean stream,
            Map<String, Object> extraParams) {
        return chatCompletion(messages, tools, stream, extraParams, null);
    }

    /**
     * Send a chat completion request with optional parameter overrides and streaming callback.
     * When {@code stream=true} and {@code onChunk} is provided, the SSE response is read
     * line-by-line and each content delta is delivered to the callback immediately.
     */
    public ChatCompletionResponse chatCompletion(
            List<ModelMessage> messages,
            List<ToolDefinition> tools,
            boolean stream,
            Map<String, Object> extraParams,
            java.util.function.Consumer<String> onChunk) {

        try {
            JSONObject requestJson = new JSONObject();
            requestJson.put("model", modelConfig.getName());
            requestJson.put("messages", buildMessagesArray(messages));
            requestJson.put("stream", stream);
            if (tools != null && !tools.isEmpty()) {
                requestJson.put("tools", buildToolsArray(tools));
            }
            if (extraParams != null) {
                for (Map.Entry<String, Object> entry : extraParams.entrySet()) {
                    requestJson.put(entry.getKey(), entry.getValue());
                }
            }

            String requestBody = requestJson.toJSONString();
            logger.debug("Sending chat completion request: {}", requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getChatCompletionUrl()))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            if (stream && onChunk != null) {
                return chatCompletionStreamInternal(request, onChunk);
            }

            HttpResponse<byte[]> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                logger.error("API error: {} - {}", response.statusCode(), errorBody);
                return ChatCompletionResponse.error("API error: " + response.statusCode());
            }

            String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            if (stream) {
                return parseStreamResponse(body);
            }
            return parseChatCompletionResponse(body);

        } catch (Exception e) {
            logger.error("Error in chat completion: {}", e.getMessage(), e);
            return ChatCompletionResponse.error(e.getMessage());
        }
    }

    private ChatCompletionResponse chatCompletionStreamInternal(
            HttpRequest request,
            java.util.function.Consumer<String> onChunk) throws Exception {

        HttpResponse<java.io.InputStream> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            logger.error("API error: {} - {}", response.statusCode(), errorBody);
            return ChatCompletionResponse.error("API error: " + response.statusCode());
        }

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        String finishReason = null;
        String model = null;

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                if (data.isEmpty()) continue;

                try {
                    JSONObject chunk = JSONObject.parseObject(data);
                    if (model == null) {
                        model = chunk.getString("model");
                    }

                    JSONArray choices = chunk.getJSONArray("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    JSONObject choice = choices.getJSONObject(0);
                    if (choice == null) continue;

                    JSONObject delta = choice.getJSONObject("delta");
                    if (delta != null) {
                        String content = delta.getString("content");
                        if (content != null && !content.isEmpty()) {
                            contentBuilder.append(content);
                            onChunk.accept(content);
                        }
                        String reasoning = delta.getString("reasoning_content");
                        if (reasoning != null && !reasoning.isEmpty()) {
                            reasoningBuilder.append(reasoning);
                        }
                    }

                    String fr = choice.getString("finish_reason");
                    if (fr != null) {
                        finishReason = fr;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to parse SSE chunk: {}", data);
                }
            }
        }

        String fullContent = contentBuilder.toString();
        if (reasoningBuilder.length() > 0) {
            fullContent = "<think>\n" + reasoningBuilder + "\n</think>\n\n" + fullContent;
        }

        ModelMessage message = new ModelMessage();
        message.setRole("assistant");
        message.setContent(fullContent);

        return new ChatCompletionResponse(
            message,
            finishReason != null ? finishReason : "stop",
            false,
            null,
            model
        );
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

    private JSONArray buildMessagesArray(List<ModelMessage> messages) {
        JSONArray array = new JSONArray();
        if (messages == null) {
            return array;
        }
        for (ModelMessage message : messages) {
            array.add(message.toJsonObject());
        }
        return array;
    }

    private String buildMessagesJson(List<ModelMessage> messages) {
        return buildMessagesArray(messages).toJSONString();
    }

    private JSONArray buildToolsArray(List<ToolDefinition> tools) {
        JSONArray array = new JSONArray();
        if (tools == null) {
            return array;
        }
        for (ToolDefinition tool : tools) {
            JSONObject function = new JSONObject();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription() != null ? tool.getDescription() : "");
            function.put("parameters", tool.getParameters() != null ? tool.getParameters() : Map.of(
                "type", "object",
                "properties", Map.of()
            ));

            JSONObject wrapper = new JSONObject();
            wrapper.put("type", "function");
            wrapper.put("function", function);
            array.add(wrapper);
        }
        return array;
    }

    private String buildToolsJson(List<ToolDefinition> tools) {
        return buildToolsArray(tools).toJSONString();
    }

    private ChatCompletionResponse parseStreamResponse(String body) {
        try {
            StringBuilder contentBuilder = new StringBuilder();
            StringBuilder reasoningBuilder = new StringBuilder();
            String finishReason = null;
            String model = null;
            long promptTokens = 0;
            long completionTokens = 0;
            long cachedTokens = 0;
            long reasoningTokens = 0;
            long totalTokens = 0;

            for (String line : body.split("\n")) {
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                if (data.isEmpty()) continue;

                try {
                    JSONObject chunk = JSONObject.parseObject(data);
                    if (model == null) {
                        model = chunk.getString("model");
                    }

                    JSONArray choices = chunk.getJSONArray("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    JSONObject choice = choices.getJSONObject(0);
                    if (choice == null) continue;

                    JSONObject delta = choice.getJSONObject("delta");
                    if (delta != null) {
                        String content = delta.getString("content");
                        if (content != null) {
                            contentBuilder.append(content);
                        }
                        String reasoning = delta.getString("reasoning_content");
                        if (reasoning != null) {
                            reasoningBuilder.append(reasoning);
                        }
                    }

                    String fr = choice.getString("finish_reason");
                    if (fr != null) {
                        finishReason = fr;
                    }

                    // Parse usage from the final chunk if present
                    JSONObject usage = chunk.getJSONObject("usage");
                    if (usage != null) {
                        promptTokens = usage.getLongValue("prompt_tokens");
                        completionTokens = usage.getLongValue("completion_tokens");
                        totalTokens = usage.getLongValue("total_tokens");
                        JSONObject promptDetails = usage.getJSONObject("prompt_tokens_details");
                        if (promptDetails != null) {
                            cachedTokens = promptDetails.getLongValue("cached_tokens");
                        }
                        JSONObject completionDetails = usage.getJSONObject("completion_tokens_details");
                        if (completionDetails != null) {
                            reasoningTokens = completionDetails.getLongValue("reasoning_tokens");
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed chunks
                    logger.debug("Failed to parse SSE chunk: {}", data);
                }
            }

            String content = contentBuilder.toString();
            // Prepend reasoning content if present
            if (reasoningBuilder.length() > 0) {
                content = "<think>\n" + reasoningBuilder + "\n</think>\n\n" + content;
            }

            ModelMessage message = new ModelMessage();
            message.setRole("assistant");
            message.setContent(content);

            ChatCompletionResponse.TokenUsage usage = totalTokens > 0
                ? new ChatCompletionResponse.TokenUsage(promptTokens, completionTokens, cachedTokens, reasoningTokens, totalTokens)
                : null;

            return new ChatCompletionResponse(
                message,
                finishReason != null ? finishReason : "stop",
                false,
                usage,
                model
            );
        } catch (Exception e) {
            logger.error("Error parsing stream response: {}", e.getMessage());
            return ChatCompletionResponse.error(e.getMessage());
        }
    }

    private ChatCompletionResponse parseChatCompletionResponse(String json) {
        try {
            JSONObject root = JSONObject.parseObject(json);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return ChatCompletionResponse.error("Failed to parse response: missing choices");
            }

            JSONObject choice = choices.getJSONObject(0);
            String finishReason = choice.getString("finish_reason");
            JSONObject messageJson = choice.getJSONObject("message");
            if (messageJson == null) {
                return ChatCompletionResponse.error("Failed to parse response: missing message");
            }

            ModelMessage message = new ModelMessage();
            message.setRole(messageJson.getString("role"));
            message.setContent(messageJson.getString("content"));

            JSONArray toolCallsJson = messageJson.getJSONArray("tool_calls");
            boolean hasToolCalls = toolCallsJson != null && !toolCallsJson.isEmpty();
            if (hasToolCalls) {
                message.setToolCalls(toolCallsJson.toJavaList(ToolCall.class));
            }

            ChatCompletionResponse.TokenUsage usage = parseUsage(root.getJSONObject("usage"));
            String model = root.getString("model");
            return new ChatCompletionResponse(
                message,
                finishReason != null ? finishReason : (hasToolCalls ? "tool_calls" : "stop"),
                hasToolCalls || "tool_calls".equals(finishReason),
                usage,
                model
            );
        } catch (Exception e) {
            logger.error("Error parsing response: {}", e.getMessage());
            return ChatCompletionResponse.error(e.getMessage());
        }
    }


    private ChatCompletionResponse.TokenUsage parseUsage(JSONObject usageJson) {
        if (usageJson == null) {
            return null;
        }
        long prompt = usageJson.getLongValue("prompt_tokens");
        long completion = usageJson.getLongValue("completion_tokens");
        long total = usageJson.getLongValue("total_tokens");
        long cached = 0;
        long reasoning = 0;
        JSONObject promptDetails = usageJson.getJSONObject("prompt_tokens_details");
        if (promptDetails != null) {
            cached = promptDetails.getLongValue("cached_tokens");
        }
        JSONObject completionDetails = usageJson.getJSONObject("completion_tokens_details");
        if (completionDetails != null) {
            reasoning = completionDetails.getLongValue("reasoning_tokens");
        }
        return new ChatCompletionResponse.TokenUsage(prompt, completion, cached, reasoning, total);
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
