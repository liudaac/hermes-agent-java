package com.nousresearch.hermes.harness.session;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

class RequestHeaderTest {

    @Test
    void differsFrom_returnsTrueForDifferentModel() {
        RequestHeader h1 = new RequestHeader("openai", "gpt-4", "system", 5, Map.of(), "initial");
        RequestHeader h2 = new RequestHeader("openai", "gpt-3.5", "system", 5, Map.of(), "change");
        assertTrue(h1.differsFrom(h2));
    }

    @Test
    void differsFrom_returnsTrueForDifferentProvider() {
        RequestHeader h1 = new RequestHeader("openai", "gpt-4", "system", 5, Map.of(), "initial");
        RequestHeader h2 = new RequestHeader("anthropic", "gpt-4", "system", 5, Map.of(), "change");
        assertTrue(h1.differsFrom(h2));
    }

    @Test
    void differsFrom_returnsFalseForIdenticalHeaders() {
        RequestHeader h1 = new RequestHeader("openai", "gpt-4", "system", 5, Map.of("temp", 0.7), "initial");
        RequestHeader h2 = new RequestHeader("openai", "gpt-4", "system", 5, Map.of("temp", 0.7), "change");
        // reason differs but differsFrom doesn't check reason (that's intentional - reason is metadata)
        // Actually check: provider, model, systemPrompt, toolCount, params
        assertFalse(h1.differsFrom(h2));
    }

    @Test
    void differsFrom_returnsTrueForNull() {
        RequestHeader h1 = new RequestHeader("openai", "gpt-4", "system", 5, Map.of(), "initial");
        assertTrue(h1.differsFrom(null));
    }

    @Test
    void differsFrom_returnsTrueForDifferentToolCount() {
        RequestHeader h1 = new RequestHeader("openai", "gpt-4", "system", 5, Map.of(), "initial");
        RequestHeader h2 = new RequestHeader("openai", "gpt-4", "system", 10, Map.of(), "change");
        assertTrue(h1.differsFrom(h2));
    }

    @Test
    void differsFrom_returnsTrueForDifferentSystemPrompt() {
        RequestHeader h1 = new RequestHeader("openai", "gpt-4", "prompt1", 5, Map.of(), "initial");
        RequestHeader h2 = new RequestHeader("openai", "gpt-4", "prompt2", 5, Map.of(), "change");
        assertTrue(h1.differsFrom(h2));
    }

    @Test
    void differsFrom_returnsTrueForDifferentParams() {
        RequestHeader h1 = new RequestHeader("openai", "gpt-4", "system", 5, Map.of("temp", 0.7), "initial");
        RequestHeader h2 = new RequestHeader("openai", "gpt-4", "system", 5, Map.of("temp", 0.9), "change");
        assertTrue(h1.differsFrom(h2));
    }

    @Test
    void toEventData_containsAllFields() {
        Map<String, Object> params = Map.of("temperature", 0.7);
        RequestHeader header = new RequestHeader("openai", "gpt-4", "system prompt", 5, params, "initial");
        Map<String, Object> data = header.toEventData();
        assertEquals("openai", data.get("provider"));
        assertEquals("gpt-4", data.get("model"));
        assertEquals(13, data.get("systemPromptLength")); // "system prompt".length()
        assertEquals(5, data.get("toolCount"));
        assertEquals(params, data.get("params"));
        assertEquals("initial", data.get("reason"));
    }

    @Test
    void toEventData_handlesNullSystemPrompt() {
        RequestHeader header = new RequestHeader("openai", "gpt-4", null, 5, Map.of(), "initial");
        Map<String, Object> data = header.toEventData();
        assertEquals("openai", data.get("provider"));
        assertFalse(data.containsKey("systemPromptLength"));
    }
}
