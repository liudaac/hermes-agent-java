package com.nousresearch.hermes.trajectory;

import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrajectoryCompressorTest {

    @Test
    void compressesTrajectoryAndTracksTokenReduction() {
        TrajectoryEntry entry = new TrajectoryEntry();
        entry.setConversations(new ArrayList<>(List.of(
            ModelMessage.system("system prompt one"),
            ModelMessage.system("system prompt two should be filtered"),
            ModelMessage.user("please do the task"),
            ModelMessage.user("please do the task"),
            ModelMessage.assistant(longText()),
            ModelMessage.user("final short question"),
            ModelMessage.assistant("final short answer")
        )));

        TrajectoryEntry compressed = new TrajectoryCompressor().compress(entry);

        assertTrue(compressed.isCompressed());
        assertNotNull(compressed.getOriginalTokenCount());
        assertNotNull(compressed.getCompressedTokenCount());
        assertTrue(compressed.getCompressedTokenCount() < compressed.getOriginalTokenCount());
        assertNotNull(compressed.getCompressionSummary());
        assertEquals(1, compressed.getConversations().stream()
            .filter(m -> "system".equals(m.getRole()))
            .count());
        assertTrue(compressed.getConversations().stream()
            .anyMatch(m -> m.getContent() != null && m.getContent().contains("[truncated")));
    }

    @Test
    void returnsAlreadyCompressedTrajectoryUnchanged() {
        TrajectoryEntry entry = new TrajectoryEntry();
        entry.setCompressed(true);
        entry.setConversations(List.of(ModelMessage.user("hello")));

        TrajectoryEntry result = new TrajectoryCompressor().compress(entry);

        assertSame(entry, result);
        assertNull(result.getOriginalTokenCount());
    }

    @Test
    void limitsConversationToMostRecentMessages() {
        TrajectoryEntry entry = new TrajectoryEntry();
        List<ModelMessage> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(ModelMessage.user("message-" + i));
        }
        entry.setConversations(messages);

        TrajectoryEntry compressed = new TrajectoryCompressor().compress(entry);

        assertEquals(20, compressed.getConversations().size());
        assertEquals("message-10", compressed.getConversations().get(0).getContent());
        assertEquals("message-29", compressed.getConversations().get(19).getContent());
    }

    private String longText() {
        return "a".repeat(700) + "tail-content";
    }
}
