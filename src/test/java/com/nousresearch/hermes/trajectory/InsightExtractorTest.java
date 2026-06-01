package com.nousresearch.hermes.trajectory;

import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InsightExtractorTest {

    @Test
    void extractsSuccessPatternsInteractionAndMemoryHints() {
        TrajectoryEntry entry = new TrajectoryEntry();
        entry.setCompleted(true);
        entry.setConversations(List.of(
            ModelMessage.user("Remember that I prefer concise answers"),
            ModelMessage.assistant("I will keep that in mind"),
            ModelMessage.user("Please create the report"),
            ModelMessage.assistant("I've completed the report successfully")
        ));

        List<String> insights = new InsightExtractor().extract(entry);

        assertTrue(insights.stream().anyMatch(s -> s.startsWith("Success pattern:")));
        assertTrue(insights.stream().anyMatch(s -> s.startsWith("Interaction pattern:")));
        assertTrue(insights.stream().anyMatch(s -> s.startsWith("User preference:")));
        assertTrue(insights.stream().anyMatch(s -> s.startsWith("Memory hint:")));
    }

    @Test
    void extractsFailureLessonsForIncompleteTrajectory() {
        TrajectoryEntry entry = new TrajectoryEntry();
        entry.setCompleted(false);
        entry.setConversations(List.of(
            ModelMessage.user("Run the command"),
            ModelMessage.assistant("Error: command failed with timeout")
        ));

        List<String> insights = new InsightExtractor().extract(entry);

        assertTrue(insights.stream().anyMatch(s -> s.startsWith("Failure lesson:")));
    }

    @Test
    void extractsToolUsagePatternFromMessages() {
        TrajectoryEntry entry = new TrajectoryEntry();
        entry.setCompleted(true);
        entry.setConversations(List.of(
            ModelMessage.assistant("Using tool: read to inspect files"),
            ModelMessage.assistant("Using tool: write to update files"),
            ModelMessage.assistant("done")
        ));

        List<String> insights = new InsightExtractor().extract(entry);

        assertTrue(insights.stream().anyMatch(s -> s.contains("Tools used: read -> write")));
    }

    @Test
    void returnsEmptyForMissingConversation() {
        TrajectoryEntry entry = new TrajectoryEntry();
        entry.setConversations(null);

        assertTrue(new InsightExtractor().extract(entry).isEmpty());
    }
}
