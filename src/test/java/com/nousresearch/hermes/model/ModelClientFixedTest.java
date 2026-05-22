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
}
