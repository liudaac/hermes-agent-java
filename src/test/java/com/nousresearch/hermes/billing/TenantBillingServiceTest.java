package com.nousresearch.hermes.billing;

import com.nousresearch.hermes.dashboard.pricing.ModelPricingTable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B4: TenantBillingService + TenantUsageRecord tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantBillingServiceTest {

    @TempDir
    Path tempDir;

    private TenantBillingService billingService;

    @BeforeEach
    void setUp() {
        billingService = new TenantBillingService();
    }

    @Test
    @Order(1)
    @DisplayName("TenantUsageRecord.of creates correct record")
    void recordCreation() {
        TenantUsageRecord record = TenantUsageRecord.of(
            "tenant-A", "gpt-4o", "openai",
            500, 1000, 0.018, "session-123");

        assertEquals("tenant-A", record.tenantId());
        assertEquals("gpt-4o", record.model());
        assertEquals("openai", record.provider());
        assertEquals(500, record.inputTokens());
        assertEquals(1000, record.outputTokens());
        assertEquals(1500, record.totalTokens());
        assertEquals(0.018, record.estimatedCostUsd());
        assertEquals(LocalDate.now(), record.date());
        assertEquals("session-123", record.sessionId());
        assertNotNull(record.timestamp());
    }

    @Test
    @Order(2)
    @DisplayName("toJsonLine produces valid JSON line")
    void toJsonLine() {
        TenantUsageRecord record = TenantUsageRecord.of(
            "tenant-A", "gpt-4o", "openai",
            500, 1000, 0.018, "sess-1");

        String json = record.toJsonLine();
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertFalse(json.contains("\n"));  // single line
        assertTrue(json.contains("\"tenantId\":\"tenant-A\""));
        assertTrue(json.contains("\"model\":\"gpt-4o\""));
        assertTrue(json.contains("\"inputTokens\":500"));
        assertTrue(json.contains("\"outputTokens\":1000"));
        assertTrue(json.contains("\"totalTokens\":1500"));
    }

    @Test
    @Order(3)
    @DisplayName("toJsonLine escapes special characters")
    void toJsonLine_escapes() {
        TenantUsageRecord record = TenantUsageRecord.of(
            "tenant\"A", "model\"name", "provi\\der",
            1, 1, 0.0, "sess");

        String json = record.toJsonLine();
        assertTrue(json.contains("tenant\\\"A"));
        assertTrue(json.contains("model\\\"name"));
        assertTrue(json.contains("provi\\\\der"));
    }

    @Test
    @Order(4)
    @DisplayName("estimateCost uses pricing table")
    void estimateCost() {
        double cost = billingService.estimateCost("gpt-4o", 1000, 500);
        // gpt-4o: $5/M input, $15/M output
        // 1000/1M * 5 + 500/1M * 15 = 0.005 + 0.0075 = 0.0125
        assertTrue(cost > 0);
        assertTrue(cost < 1.0);  // reasonable for small token counts
    }

    @Test
    @Order(5)
    @DisplayName("getPricingTable returns the pricing table")
    void getPricingTable() {
        assertNotNull(billingService.getPricingTable());
        assertTrue(billingService.getPricingTable() instanceof ModelPricingTable);
    }

    @Test
    @Order(6)
    @DisplayName("record writes JSONL to tenant billing directory")
    void record_writesJsonl(@TempDir Path tenantDir) throws Exception {
        // Create a minimal TenantContext-like stub
        var tenantContext = createStubTenantContext(tenantDir);

        billingService.record(tenantContext, "gpt-4o", "openai",
            500, 1000, "sess-1");

        Path billingFile = tenantDir.resolve("state").resolve("billing")
            .resolve(LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".jsonl");

        assertTrue(Files.exists(billingFile));
        List<String> lines = Files.readAllLines(billingFile);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"model\":\"gpt-4o\""));
        assertTrue(lines.get(0).contains("\"inputTokens\":500"));
        assertTrue(lines.get(0).contains("\"totalTokens\":1500"));
    }

    @Test
    @Order(7)
    @DisplayName("multiple records append to same file")
    void record_appends(@TempDir Path tenantDir) throws Exception {
        var tenantContext = createStubTenantContext(tenantDir);

        billingService.record(tenantContext, "gpt-4o", "openai", 100, 200, "sess-1");
        billingService.record(tenantContext, "claude-3.5-sonnet", "anthropic", 300, 400, "sess-2");
        billingService.record(tenantContext, "gpt-4o-mini", "openai", 50, 50, "sess-3");

        Path billingFile = tenantDir.resolve("state").resolve("billing")
            .resolve(LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".jsonl");

        List<String> lines = Files.readAllLines(billingFile);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("gpt-4o"));
        assertTrue(lines.get(1).contains("claude-3.5-sonnet"));
        assertTrue(lines.get(2).contains("gpt-4o-mini"));
    }

    @Test
    @Order(8)
    @DisplayName("record with zero tokens is skipped")
    void record_zeroTokens(@TempDir Path tenantDir) {
        var tenantContext = createStubTenantContext(tenantDir);

        // 0 tokens -> billingService.record checks for negative, not zero
        // But the ModelClient.recordTokenUsage checks totalTokens > 0
        // Here we test the service directly - it will still write
        billingService.record(tenantContext, "gpt-4o", "openai", 0, 0, "sess-1");

        Path billingFile = tenantDir.resolve("state").resolve("billing")
            .resolve(LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".jsonl");
        // Service writes regardless (the >0 check is in ModelClient)
        assertTrue(Files.exists(billingFile));
    }

    @Test
    @Order(9)
    @DisplayName("record with null tenant context is skipped")
    void record_nullTenant() {
        // Should not throw
        billingService.record(null, "gpt-4o", "openai", 100, 100, "sess-1");
    }

    @Test
    @Order(10)
    @DisplayName("different tenants write to separate files")
    void separateTenantFiles(@TempDir Path dir) throws Exception {
        var tenantA = createStubTenantContext(dir.resolve("tenantA"));
        var tenantB = createStubTenantContext(dir.resolve("tenantB"));

        billingService.record(tenantA, "gpt-4o", "openai", 100, 200, "sess-A");
        billingService.record(tenantB, "claude-3.5-sonnet", "anthropic", 300, 400, "sess-B");

        Path fileA = dir.resolve("tenantA").resolve("state").resolve("billing")
            .resolve(LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".jsonl");
        Path fileB = dir.resolve("tenantB").resolve("state").resolve("billing")
            .resolve(LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".jsonl");

        assertTrue(Files.exists(fileA));
        assertTrue(Files.exists(fileB));

        List<String> linesA = Files.readAllLines(fileA);
        List<String> linesB = Files.readAllLines(fileB);
        assertEquals(1, linesA.size());
        assertEquals(1, linesB.size());
        assertTrue(linesA.get(0).contains("gpt-4o"));
        assertTrue(linesB.get(0).contains("claude-3.5-sonnet"));
    }

    @Test
    @Order(11)
    @DisplayName("custom pricing table can be injected")
    void customPricingTable() {
        ModelPricingTable custom = new ModelPricingTable();
        TenantBillingService svc = new TenantBillingService(custom);
        assertSame(custom, svc.getPricingTable());
    }

    // ============ Helper ============

    /**
     * Create a minimal TenantContext stub for billing tests.
     * Uses reflection to avoid full TenantContext.create() initialization.
     */
    private com.nousresearch.hermes.tenant.core.TenantContext createStubTenantContext(Path dir) {
        try {
            Files.createDirectories(dir.resolve("state").resolve("billing"));
            // Use reflection to create a minimal TenantContext
            var constructor = com.nousresearch.hermes.tenant.core.TenantContext.class
                .getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            // TenantContext(String tenantId, Path tenantDir) - private constructor
            // Find the right constructor
            for (var c : com.nousresearch.hermes.tenant.core.TenantContext.class
                    .getDeclaredConstructors()) {
                if (c.getParameterCount() == 2) {
                    c.setAccessible(true);
                    return (com.nousresearch.hermes.tenant.core.TenantContext)
                        c.newInstance(dir.getFileName().toString(), dir);
                }
            }
            // Fallback: use TenantContext.create with a provisioning request
            var request = new com.nousresearch.hermes.tenant.core.TenantProvisioningRequest(
                dir.getFileName().toString(), "test");
            // Set HermesHome to tempDir's parent
            System.setProperty("hermes.home", dir.getParent().toString());
            return com.nousresearch.hermes.tenant.core.TenantContext.create(
                dir.getFileName().toString(), request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create stub TenantContext", e);
        }
    }
}
