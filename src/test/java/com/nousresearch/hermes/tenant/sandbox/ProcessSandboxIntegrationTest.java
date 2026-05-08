package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ProcessSandbox 集成测试
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProcessSandboxIntegrationTest {

    @TempDir
    Path tempDir;

    private TenantContext context;

    @BeforeAll
    void setUp() throws Exception {
        // 创建测试租户
        TenantProvisioningRequest request = TenantProvisioningRequest.builder("test-tenant", "test-user")
            .processSandboxConfig(
                ProcessSandboxConfig.builder()
                    .workDirectory(tempDir)
                    .commandWhitelist(Set.of("echo", "ls", "cat", "pwd", "sleep"))
                    .build()
            )
            .build();

        // 初始化 TenantContext
        context = TenantContext.create("test-tenant", request);
    }

    @Test
    @DisplayName("通过 TenantContext 执行命令")
    void testExecViaContext() {
        ProcessResult result = context.exec(
            List.of("echo", "Hello from sandbox"),
            ProcessOptions.builder().build()
        );

        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("Hello from sandbox"));
    }

    @Test
    @DisplayName("工作目录应该被限制")
    void testWorkingDirectoryRestriction() {
        ProcessResult result = context.exec(
            List.of("pwd"),
            ProcessOptions.builder().build()
        );

        assertTrue(result.isSuccess());
        // pwd 应该返回租户的工作目录
        assertTrue(result.getStdout().contains("test-tenant") || 
                   result.getStdout().contains("sandbox"));
    }

    @Test
    @DisplayName("长时间运行的命令应该被超时终止")
    void testLongRunningCommandTimeout() {
        long startTime = System.currentTimeMillis();

        ProcessResult result = context.exec(
            List.of("sleep", "10"),
            ProcessOptions.builder().timeoutSeconds(2).build()
        );

        long duration = System.currentTimeMillis() - startTime;

        assertTrue(result.isTimedOut());
        assertTrue(duration < 5000, "Should timeout within 5 seconds");
    }

    @Test
    @DisplayName("并发执行多个命令")
    void testConcurrentExecution() throws Exception {
        List<Thread> threads = new ArrayList<>();
        List<ProcessResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 5; i++) {
            final int index = i;
            Thread t = new Thread(() -> {
                ProcessResult result = context.exec(
                    List.of("echo", "Thread " + index),
                    ProcessOptions.builder().build()
                );
                results.add(result);
            });
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        assertEquals(5, results.size());
        for (ProcessResult result : results) {
            assertTrue(result.isSuccess());
        }
    }

    @AfterAll
    void tearDown() {
        if (context != null) {
            context.destroy(false);
        }
    }
}
