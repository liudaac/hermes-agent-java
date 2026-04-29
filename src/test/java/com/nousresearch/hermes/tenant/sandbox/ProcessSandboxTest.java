package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ProcessSandbox 单元测试
 */
class ProcessSandboxTest {

    @TempDir
    Path tempDir;

    private TenantContext mockContext;
    private ProcessSandboxConfig config;
    private ProcessSandbox sandbox;

    @BeforeEach
    void setUp() {
        mockContext = mock(TenantContext.class);
        when(mockContext.getTenantId()).thenReturn("test-tenant");
        when(mockContext.getTenantDir()).thenReturn(tempDir);

        config = ProcessSandboxConfig.builder()
            .allowCommand("echo")
            .allowCommand("cat")
            .allowCommand("ls")
            .blockCommand("rm")
            .blockCommand("sudo")
            .workDirectory(tempDir)
            .build();

        sandbox = new ProcessSandbox(mockContext, config);
    }

    @Test
    void testAllowedCommandExecution() {
        ProcessResult result = sandbox.exec(
            List.of("echo", "hello"),
            ProcessOptions.builder().build()
        );

        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("hello"));
    }

    @Test
    void testBlockedCommandExecution() {
        ProcessSandboxException exception = assertThrows(
            ProcessSandboxException.class,
            () -> sandbox.exec(
                List.of("rm", "-rf", "/"),
                ProcessOptions.builder().build()
            )
        );

        assertTrue(exception.getMessage().contains("blocked"));
    }

    @Test
    void testCommandTimeout() {
        ProcessResult result = sandbox.exec(
            List.of("sleep", "10"),
            ProcessOptions.builder()
                .timeoutSeconds(1)
                .build()
        );

        assertFalse(result.isSuccess());
        assertTrue(result.isTimedOut());
    }

    @Test
    void testWorkingDirectoryRestriction() {
        ProcessResult result = sandbox.exec(
            List.of("pwd"),
            ProcessOptions.builder()
                .workDirectory(tempDir)
                .build()
        );

        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().trim().startsWith(tempDir.toString()));
    }

    @Test
    void testEnvironmentVariableSanitization() {
        ProcessResult result = sandbox.exec(
            List.of("env"),
            ProcessOptions.builder().build()
        );

        assertTrue(result.isSuccess());
        // 确保敏感变量被清理
        assertFalse(result.getStdout().contains("SECRET"));
        assertFalse(result.getStdout().contains("PASSWORD"));
    }

    @Test
    void testMaxPidsLimit() {
        ProcessOptions options = ProcessOptions.builder()
            .maxPids(5)
            .timeoutSeconds(5)
            .build();

        // 尝试创建超过限制的进程
        ProcessSandboxException exception = assertThrows(
            ProcessSandboxException.class,
            () -> sandbox.exec(
                List.of("bash", "-c", "for i in {1..10}; do sleep 1 & done"),
                options
            )
        );

        assertTrue(exception.getMessage().contains("PID") || 
                   exception.getMessage().contains("limit"));
    }
}
