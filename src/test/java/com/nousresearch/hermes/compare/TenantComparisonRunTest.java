package com.nousresearch.hermes.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantComparisonRunTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesAndDeserializesRunState() throws Exception {
        TenantComparisonRun run = new TenantComparisonRun("topic", 2, List.of("tenant-a", "tenant-b"));
        run.markRunning();
        run.addEvent("tenant-a", "user", "hello");
        run.addEvent("tenant-a", "assistant", "hi");
        run.markCompleted("final conclusion");

        String json = mapper.writeValueAsString(run);
        TenantComparisonRun restored = mapper.readValue(json, TenantComparisonRun.class);

        assertEquals(run.getId(), restored.getId());
        assertEquals("topic", restored.getTopic());
        assertEquals(2, restored.getRounds());
        assertEquals(TenantComparisonRun.Status.COMPLETED, restored.getStatus());
        assertEquals("final conclusion", restored.getConclusion());
        assertEquals(2, restored.getParticipants().size());
        assertEquals(2, restored.getEvents().size());
        assertEquals("tenant-a", restored.getEvents().get(0).tenantId());
    }

    @Test
    void requestStopIsNotPersistedAsJsonProperty() throws Exception {
        TenantComparisonRun run = new TenantComparisonRun("topic", 1, List.of("tenant-a", "tenant-b"));
        run.requestStop();

        String json = mapper.writeValueAsString(run);

        assertFalse(json.contains("stopRequested"));
    }
}
