package com.nousresearch.hermes.performance;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks for tenant operations.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TenantPerformanceBenchmark {

    private static final int WARMUP_ITERATIONS = 10;
    private static final int BENCHMARK_ITERATIONS = 100;
    private static final int CONCURRENT_TENANTS = 10;

    private TenantManager tenantManager;
    private HermesConfig hermesConfig;

    @BeforeEach
    void setUp() {
        hermesConfig = new HermesConfig(
            System.getenv("OPENAI_API_KEY"),
            "https://api.openai.com/v1",
            "gpt-4"
        );

        tenantManager = new TenantManager();
        tenantManager.initializeDefaultTenant();
    }

    @AfterEach
    void tearDown() {
        if (tenantManager != null) {
            tenantManager.shutdown();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Benchmark: Tenant creation performance")
    void benchmarkTenantCreation() {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            createTenant("warmup-" + i);
        }

        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            createTenant("bench-" + i);
        }
        long endTime = System.nanoTime();

        double avgTimeMs = (endTime - startTime) / (BENCHMARK_ITERATIONS * 1_000_000.0);
        System.out.println("Average tenant creation time: " + avgTimeMs + " ms");

        assertTrue(avgTimeMs < 100, "Tenant creation should be under 100ms");
    }

    @Test
    @Order(2)
    @DisplayName("Benchmark: File operations performance")
    void benchmarkFileOperations() {
        TenantContext tenant = createTenant("file-bench");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            tenant.getFileSandbox().writeFile("warmup.txt", "content");
            tenant.getFileSandbox().readFile("warmup.txt");
        }

        // Benchmark write
        long writeStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            tenant.getFileSandbox().writeFile("bench-" + i + ".txt", "content" + i);
        }
        long writeEnd = System.nanoTime();
        double avgWriteMs = (writeEnd - writeStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

        // Benchmark read
        long readStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            tenant.getFileSandbox().readFile("bench-" + i + ".txt");
        }
        long readEnd = System.nanoTime();
        double avgReadMs = (readEnd - readStart) / (BENCHMARK_ITERATIONS * 1_000_000.0);

        System.out.println("Average file write time: " + avgWriteMs + " ms");
        System.out.println("Average file read time: " + avgReadMs + " ms");

        assertTrue(avgWriteMs < 10, "File write should be under 10ms");
        assertTrue(avgReadMs < 5, "File read should be under 5ms");
    }

    @Test
    @Order(3)
    @DisplayName("Benchmark: Concurrent tenant operations")
    void benchmarkConcurrentTenants() throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_TENANTS);
        List<Future<Long>> futures = new ArrayList<>();

        // Warmup
        for (int i = 0; i < CONCURRENT_TENANTS; i++) {
            final int idx = i;
            executor.submit(() -> {
                TenantContext tenant = createTenant("concurrent-warmup-" + idx);
                tenant.getFileSandbox().writeFile("test.txt", "content");
                return 0L;
            });
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Benchmark
        executor = Executors.newFixedThreadPool(CONCURRENT_TENANTS);
        long startTime = System.nanoTime();

        for (int i = 0; i < CONCURRENT_TENANTS; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                long opStart = System.nanoTime();
                TenantContext tenant = createTenant("concurrent-" + idx);
                for (int j = 0; j < 10; j++) {
                    tenant.getFileSandbox().writeFile("file-" + j + ".txt", "content" + j);
                    tenant.getFileSandbox().readFile("file-" + j + ".txt");
                }
                long opEnd = System.nanoTime();
                return (opEnd - opStart) / 1_000_000; // Convert to ms
            }));
        }

        long totalTime = 0;
        for (Future<Long> future : futures) {
            totalTime += future.get();
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long endTime = System.nanoTime();
        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        double avgTimeMs = totalTime / CONCURRENT_TENANTS;

        System.out.println("Concurrent tenant operations:");
        System.out.println("  Total time: " + totalTimeMs + " ms");
        System.out.println("  Average per tenant: " + avgTimeMs + " ms");
        System.out.println("  Throughput: " + (CONCURRENT_TENANTS * 20 / (totalTimeMs / 1000.0)) + " ops/sec");

        assertTrue(avgTimeMs < 500, "Concurrent tenant operations should be under 500ms");
    }

    @Test
    @Order(4)
    @DisplayName("Benchmark: Memory usage with multiple tenants")
    void benchmarkMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();

        // Baseline memory
        System.gc();
        long baselineMemory = runtime.totalMemory() - runtime.freeMemory();

        // Create tenants and measure memory
        List<TenantContext> tenants = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tenants.add(createTenant("memory-" + i));
        }

        System.gc();
        long afterCreation = runtime.totalMemory() - runtime.freeMemory();
        long memoryPerTenant = (afterCreation - baselineMemory) / 50;

        System.out.println("Memory usage per tenant: " + (memoryPerTenant / 1024) + " KB");

        assertTrue(memoryPerTenant < 10 * 1024 * 1024, "Memory per tenant should be under 10MB");
    }

    @Test
    @Order(5)
    @DisplayName("Benchmark: Agent creation and message processing")
    void benchmarkAgentPerformance() {
        TenantContext tenant = createTenant("agent-bench");

        // Warmup
        for (int i = 0; i < 5; i++) {
            TenantAwareAIAgent agent = TenantAwareAIAgent.forTenant("agent-bench", hermesConfig);
        }

        // Benchmark agent creation
        long createStart = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            TenantAwareAIAgent agent = TenantAwareAIAgent.forTenant("agent-bench", hermesConfig);
        }
        long createEnd = System.nanoTime();
        double avgCreateMs = (createEnd - createStart) / (20 * 1_000_000.0);

        System.out.println("Average agent creation time: " + avgCreateMs + " ms");
        assertTrue(avgCreateMs < 50, "Agent creation should be under 50ms");
    }

    @Test
    @Order(6)
    @DisplayName("Benchmark: Quota checking performance")
    void benchmarkQuotaChecking() {
        TenantContext tenant = createTenant("quota-bench");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try {
                tenant.getQuotaManager().checkDailyRequestQuota();
            } catch (Exception e) {
                // Ignore
            }
        }

        // Benchmark
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            try {
                tenant.getQuotaManager().checkDailyRequestQuota();
            } catch (Exception e) {
                // Ignore quota exceeded in benchmark
            }
        }
        long endTime = System.nanoTime();

        double avgTimeMs = (endTime - startTime) / (BENCHMARK_ITERATIONS * 1_000_000.0);
        System.out.println("Average quota check time: " + avgTimeMs + " ms");

        assertTrue(avgTimeMs < 1, "Quota check should be under 1ms");
    }

    private TenantContext createTenant(String tenantId) {
        TenantProvisioningRequest request = new TenantProvisioningRequest()
            .setTenantId(tenantId)
            .withDefaultQuota()
            .withDefaultSecurityPolicy();

        if (tenantManager.exists(tenantId)) {
            return tenantManager.getTenant(tenantId);
        }

        return tenantManager.createTenant(request);
    }
}
