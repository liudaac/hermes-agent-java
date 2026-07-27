package com.nousresearch.hermes.sdk;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SDK tests - pure unit tests, no HTTP server needed.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HermesClientTest {

    // ============ Json parser tests ============

    @Test
    @Order(1)
    @DisplayName("Json.getString extracts string value")
    void json_getString() {
        assertEquals("hello", Json.getString("{\"name\":\"hello\"}", "name"));
        assertEquals("world", Json.getString("{\"a\":1,\"msg\":\"world\"}", "msg"));
        assertNull(Json.getString("{\"a\":1}", "b"));
    }

    @Test
    @Order(2)
    @DisplayName("Json.getString handles escaped quotes")
    void json_escapedQuotes() {
        assertEquals("say \"hi\"", Json.getString("{\"v\":\"say \\\"hi\\\"\"}", "v"));
    }

    @Test
    @Order(3)
    @DisplayName("Json.getLong extracts number")
    void json_getLong() {
        assertEquals(4500L, Json.getLong("{\"durationMs\":4500}", "durationMs"));
        assertEquals(0L, Json.getLong("{\"a\":\"x\"}", "b"));
    }

    @Test
    @Order(4)
    @DisplayName("Json.getBoolean extracts boolean")
    void json_getBoolean() {
        assertTrue(Json.getBoolean("{\"chainMode\":true}", "chainMode"));
        assertFalse(Json.getBoolean("{\"chainMode\":false}", "chainMode"));
        assertFalse(Json.getBoolean("{}", "chainMode"));
    }

    @Test
    @Order(5)
    @DisplayName("Json.getObject extracts nested object")
    void json_getObject() {
        String json = "{\"goal\":\"test\",\"plan\":{\"goal\":\"migrate\",\"steps\":[]}}";
        String plan = Json.getObject(json, "plan");
        assertNotNull(plan);
        assertTrue(plan.contains("migrate"));
        assertTrue(plan.startsWith("{"));
        assertTrue(plan.endsWith("}"));
    }

    @Test
    @Order(6)
    @DisplayName("Json.esc escapes special characters")
    void json_esc() {
        assertEquals("hello\\\\world", Json.esc("hello\\world"));
        assertEquals("say \\\"hi\\\"", Json.esc("say \"hi\""));
        assertEquals("line1\\nline2", Json.esc("line1\nline2"));
    }

    // ============ MessageResponse parsing ============

    @Test
    @Order(7)
    @DisplayName("MessageResponse.parse direct mode")
    void messageResponse_direct() {
        String json = "{\"reply\":\"hello\",\"durationMs\":150,\"workspaceId\":\"ws1\",\"chainMode\":false}";
        var res = HermesClient.MessageResponse.parse(json);

        assertEquals("hello", res.reply());
        assertEquals(150, res.durationMs());
        assertEquals("ws1", res.workspaceId());
        assertFalse(res.chainMode());
        assertNull(res.traceId());
        assertNull(res.planJson());
    }

    @Test
    @Order(8)
    @DisplayName("MessageResponse.parse chain mode with plan")
    void messageResponse_chain() {
        String json = "{\"reply\":\"Goal: test\\nSteps:\",\"durationMs\":5000,\"chainMode\":true," +
            "\"traceId\":\"trace_abc123\"," +
            "\"plan\":{\"goal\":\"test\",\"stepCount\":2,\"passthrough\":false," +
            "\"steps\":[{\"id\":\"s1\",\"action\":\"read\"}]," +
            "\"successCriteria\":[\"done\"]}}";

        var res = HermesClient.MessageResponse.parse(json);

        assertTrue(res.chainMode());
        assertEquals("trace_abc123", res.traceId());
        assertNotNull(res.planJson());
        assertEquals("test", res.planField("goal"));
        assertEquals("2", res.planField("stepCount"));
    }

    // ============ TaskStatus parsing ============

    @Test
    @Order(9)
    @DisplayName("TaskStatus.parse completed")
    void taskStatus_completed() {
        String json = "{\"taskId\":\"t1\",\"status\":\"COMPLETED\",\"result\":\"done\",\"error\":null}";
        var s = HermesClient.TaskStatus.parse(json);

        assertEquals("t1", s.taskId());
        assertEquals("COMPLETED", s.status());
        assertEquals("done", s.result());
        assertTrue(s.isTerminal());
        assertTrue(s.isCompleted());
        assertFalse(s.isFailed());
    }

    @Test
    @Order(10)
    @DisplayName("TaskStatus.parse failed")
    void taskStatus_failed() {
        String json = "{\"taskId\":\"t1\",\"status\":\"FAILED\",\"result\":null,\"error\":\"timeout\"}";
        var s = HermesClient.TaskStatus.parse(json);

        assertTrue(s.isTerminal());
        assertTrue(s.isFailed());
        assertEquals("timeout", s.error());
    }

    // ============ InterruptResponse parsing ============

    @Test
    @Order(11)
    @DisplayName("InterruptResponse.parse interrupting")
    void interruptResponse_parse() {
        String json = "{\"taskId\":\"t1\",\"status\":\"INTERRUPTING\",\"message\":\"Chain will stop\"}";
        var r = HermesClient.InterruptResponse.parse(json);

        assertEquals("t1", r.taskId());
        assertEquals("INTERRUPTING", r.status());
        assertTrue(r.isInterrupting());
        assertEquals("Chain will stop", r.message());
    }

    // ============ Builder ============

    @Test
    @Order(12)
    @DisplayName("Builder requires API key")
    void builder_requiresApiKey() {
        assertThrows(IllegalArgumentException.class, () ->
            HermesClient.builder().baseUrl("http://x").build());
    }

    @Test
    @Order(13)
    @DisplayName("Builder creates client with valid params")
    void builder_valid() {
        var client = HermesClient.builder()
            .baseUrl("http://localhost:8080/")
            .apiKey("ak_test")
            .build();
        assertNotNull(client);
    }

    // ============ Exception ============

    @Test
    @Order(14)
    @DisplayName("HermesApiException stores status and body")
    void apiException() {
        var e = new HermesClient.HermesApiException(404, "not found");
        assertEquals(404, e.getStatusCode());
        assertEquals("not found", e.getResponseBody());
        assertTrue(e.getMessage().contains("404"));
    }

    // ============ Edge cases ============

    @Test
    @Order(15)
    @DisplayName("Json.getString handles null input")
    void json_nullInput() {
        assertNull(Json.getString(null, "key"));
        assertNull(Json.getString("{}", null));
    }

    @Test
    @Order(16)
    @DisplayName("Json.getObject returns null for missing key")
    void json_missingObject() {
        assertNull(Json.getObject("{\"a\":1}", "plan"));
    }

    @Test
    @Order(17)
    @DisplayName("Json.getString handles nested objects")
    void json_nestedKey() {
        // The simple parser searches for "key" anywhere, so it can find
        // keys at any nesting level
        String json = "{\"outer\":{\"inner\":\"value\"}}";
        assertEquals("value", Json.getString(json, "inner"));
    }
}
