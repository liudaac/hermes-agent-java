package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
            .commandWhitelist(Set.of("echo", "cat", "ls"))
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

}
