package com.nousresearch.hermes.tenant.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ControlActionAuditTest {
    @TempDir
    Path tempDir;

    @Test
    void controlActionEventsAreWrittenWithActorAndReason() throws Exception {
        TenantAuditLogger logger = new TenantAuditLogger(tempDir);
        logger.log(AuditEvent.CONTROL_AGENT_OVERRIDE_CHANGED, Map.of(
            "tenantId", "tenant-a",
            "actor", "operator-1",
            "reason", "agent unhealthy",
            "agent", "agent-1",
            "mode", "disabled"
        ));

        Path auditLog = tempDir.resolve("audit.log");
        long deadline = System.currentTimeMillis() + 2_000;
        while ((!Files.exists(auditLog) || Files.readString(auditLog).isBlank()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        String content = Files.readString(auditLog);
        assertTrue(content.contains("CONTROL_AGENT_OVERRIDE_CHANGED"));
        assertTrue(content.contains("operator-1"));
        assertTrue(content.contains("agent unhealthy"));
    }
}
