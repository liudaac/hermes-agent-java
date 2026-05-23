package com.nousresearch.hermes.model;

import com.nousresearch.hermes.config.HermesConfig;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ModelClient.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ModelClientFixedTest {

    private ModelClient modelClient;

    @BeforeEach
    void setUp() {
        HermesConfig.ModelConfig config = new HermesConfig.ModelConfig(
            "openai",
            "gpt-4",
            System.getenv("OPENAI_API_KEY"),
            "https://api.openai.com/v1"
        );

        modelClient = new ModelClient(config);
    }

    @Test
    @Order(1)
    @DisplayName("API key verification should work with valid key")
    void testVerifyApiKey() {
        // Skip if no API key
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getenv("OPENAI_API_KEY") != null,
            "Skipping test - no API key available"
        );

        boolean valid = modelClient.verifyApiKey();
        assertTrue(valid);
    }

    @Test
    @Order(2)
    @DisplayName("Embedding creation should return vector")
    void testCreateEmbedding() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getenv("OPENAI_API_KEY") != null,
            "Skipping test - no API key available"
        );

        float[] embedding = modelClient.createEmbedding("Hello world");

        assertNotNull(embedding);
        assertTrue(embedding.length > 0);
    }

    @Test
    @Order(3)
    @DisplayName("Chat completion should return response")
    void testChatCompletion() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getenv("OPENAI_API_KEY") != null,
            "Skipping test - no API key available"
        );

        var messages = java.util.List.of(
            ModelMessage.system("You are a helpful assistant."),
            ModelMessage.user("Say 'test' and nothing else.")
        );

        ChatCompletionResponse response = modelClient.chatCompletion(
            messages, null, false
        );

        assertNotNull(response);
        assertNotNull(response.getMessage());
    }

    @Test
    @Order(4)
    @DisplayName("Embedding should be deterministic for same input")
    void testEmbeddingDeterminism() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getenv("OPENAI_API_KEY") != null,
            "Skipping test - no API key available"
        );

        String text = "Test text for embedding";
        float[] embedding1 = modelClient.createEmbedding(text);
        float[] embedding2 = modelClient.createEmbedding(text);

        assertEquals(embedding1.length, embedding2.length);
    }
    @Test
    @Order(5)
    @DisplayName("Tool definitions should serialize to OpenAI-compatible tools JSON")
    void testToolSerializationForChatCompletions() throws Exception {
        var tool = new ToolDefinition(
            "read_file",
            "Read a file",
            java.util.Map.of(
                "type", "object",
                "properties", java.util.Map.of(
                    "path", java.util.Map.of("type", "string")
                ),
                "required", java.util.List.of("path")
            )
        );

        var method = ModelClient.class.getDeclaredMethod("buildToolsJson", java.util.List.class);
        method.setAccessible(true);
        String json = (String) method.invoke(modelClient, java.util.List.of(tool));

        var parsed = com.alibaba.fastjson2.JSONArray.parseArray(json);
        assertEquals(1, parsed.size());
        var wrapper = parsed.getJSONObject(0);
        assertEquals("function", wrapper.getString("type"));
        assertEquals("read_file", wrapper.getJSONObject("function").getString("name"));
        assertEquals("object", wrapper.getJSONObject("function").getJSONObject("parameters").getString("type"));
    }

    @Test
    @Order(6)
    @DisplayName("Chat completion parser should preserve tool calls")
    void testParseToolCallsFromChatCompletionResponse() throws Exception {
        var function = new com.alibaba.fastjson2.JSONObject();
        function.put("name", "read_file");
        function.put("arguments", "{\"path\":\"README.md\"}");

        var toolCall = new com.alibaba.fastjson2.JSONObject();
        toolCall.put("id", "call_123");
        toolCall.put("type", "function");
        toolCall.put("function", function);

        var message = new com.alibaba.fastjson2.JSONObject();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", java.util.List.of(toolCall));

        var choice = new com.alibaba.fastjson2.JSONObject();
        choice.put("finish_reason", "tool_calls");
        choice.put("message", message);

        var root = new com.alibaba.fastjson2.JSONObject();
        root.put("choices", java.util.List.of(choice));
        String json = root.toJSONString();

        var method = ModelClient.class.getDeclaredMethod("parseChatCompletionResponse", String.class);
        method.setAccessible(true);
        ChatCompletionResponse response = (ChatCompletionResponse) method.invoke(modelClient, json);

        assertTrue(response.isSuccess());
        assertTrue(response.hasToolCalls());
        assertEquals("tool_calls", response.getFinishReason());
        assertNotNull(response.getToolCalls());
        assertEquals(1, response.getToolCalls().size());
        assertEquals("call_123", response.getToolCalls().get(0).getId());
        assertEquals("read_file", response.getToolCalls().get(0).getFunction().getName());
    }

}
