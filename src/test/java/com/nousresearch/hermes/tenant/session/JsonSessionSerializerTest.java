package com.nousresearch.hermes.tenant.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonSessionSerializerTest {

    @Test
    void roundTripsSessionDataWithMetadataAndMessages() {
        JsonSessionSerializer serializer = new JsonSessionSerializer();
        SessionSerializer.SessionData original = new SessionSerializer.SessionData(
            "session-a",
            "tenant-a",
            "node-a",
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-01T00:05:00Z"),
            Map.of("channel", "qqbot", "turns", 3),
            true,
            List.of(
                SessionSerializer.ConversationMessage.system("system"),
                SessionSerializer.ConversationMessage.user("hello"),
                SessionSerializer.ConversationMessage.assistant("hi"),
                SessionSerializer.ConversationMessage.toolRequest("read", "file.txt"),
                SessionSerializer.ConversationMessage.toolResponse("read", "content")
            )
        );

        byte[] bytes = serializer.serialize(original);
        SessionSerializer.SessionData restored = serializer.deserialize(bytes);

        assertNotNull(restored);
        assertEquals(original.sessionId(), restored.sessionId());
        assertEquals(original.tenantId(), restored.tenantId());
        assertEquals(original.nodeId(), restored.nodeId());
        assertEquals(original.createdAt(), restored.createdAt());
        assertEquals(original.lastActivity(), restored.lastActivity());
        assertEquals("qqbot", restored.metadata().get("channel"));
        assertEquals(3, ((Number) restored.metadata().get("turns")).intValue());
        assertEquals(5, restored.messageCount());
        assertTrue(restored.messages().get(1).isUser());
        assertTrue(restored.messages().get(2).isAssistant());
        assertTrue(restored.messages().get(3).isTool());
        assertEquals("read", restored.messages().get(3).toolName());
        assertEquals("content", restored.messages().get(4).toolOutput());
    }

    @Test
    void returnsNullForInvalidOrEmptyInput() {
        JsonSessionSerializer serializer = new JsonSessionSerializer();

        assertNull(serializer.deserialize(null));
        assertNull(serializer.deserialize(new byte[0]));
        assertNull(serializer.deserialize("{broken".getBytes()));
        assertNull(serializer.deserializeFromString(null));
        assertNull(serializer.deserializeFromString(""));
    }

    @Test
    void compressedSerializerProducesCompactJson() {
        SessionSerializer serializer = JsonSessionSerializer.compressed();
        SessionSerializer.SessionData data = SessionSerializer.SessionData.withMetadata(
            "session-a",
            "tenant-a",
            "node-a",
            Map.of("k", "v")
        );

        String json = new String(serializer.serialize(data));

        assertFalse(json.contains("\n"));
        assertFalse(json.contains("  "));
        assertEquals("session-a", serializer.deserialize(json.getBytes()).sessionId());
    }

    @Test
    void addMessageReturnsNewSessionData() {
        SessionSerializer.SessionData data = SessionSerializer.SessionData.create("s", "t", "n");
        SessionSerializer.SessionData updated = data.addMessage(SessionSerializer.ConversationMessage.user("hello"));

        assertEquals(0, data.messageCount());
        assertEquals(1, updated.messageCount());
        assertEquals("hello", updated.messages().get(0).content());
    }
}
