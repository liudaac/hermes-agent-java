package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;

/**
 * ProcessSandbox 单元测试
 */
public class ProcessSandboxTest {

    @TempDir
    Path tempDir;

    private TenantContext mockContext;
    private ProcessSandbox sandbox;

    @BeforeEach
    void setUp() {
        mockContext = mock(TenantContext.class);
        when(mockContext.getTenantId()).thenReturn("test-tenant");
        
        ProcessSandboxConfig config = ProcessSandboxConfig.builder()
            .workDirectory(tempDir)
            .commandWhitelist(Set.of("echo", "ls", "cat"))
            .commandBlacklist(Set.of("rm", "mkfs"))
            .build();
        
        sandbox = new ProcessSandbox(mockContext, config);
    }

    @Test
    @DisplayName("执行简单命令应该成功")
    void testExecuteSimpleCommand() {
        ProcessResult result = sandbox.exec(
            List.of("echo", "hello"),
            ProcessOptions.builder().build()
        );
        
        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("hello"));
    }

    @Test
    @DisplayName("黑名单命令应该被拒绝")
    void testBlacklistedCommand() {
        ProcessSandboxException exception = assertThrows(
            ProcessSandboxException.class,
            () -> sandbox.exec(List.of("rm", "-rf", "/"), null)
        );
        
        assertTrue(exception.getMessage().contains("not allowed"));
    }

    @Test
    @DisplayName("不在白名单的命令应该被拒绝")
    void testNotInWhitelist() {
        ProcessSandboxConfig strictConfig = ProcessSandboxConfig.builder()
            .commandWhitelist(Set.of("echo"))  // 只允许 echo
            .workDirectory(tempDir)
            .build();
        
        ProcessSandbox strictSandbox = new ProcessSandbox(mockContext, strictConfig);
        
        // ls 不在白名单中，应该被拒绝
        ProcessSandboxException exception = assertThrows(
            ProcessSandboxException.class,
            () -> strictSandbox.exec(List.of("ls"), null)
        );
        
        assertTrue(exception.getMessage().contains("not allowed"));
    }

    @Test
    @DisplayName("超时应该触发")
    void testTimeout() {
        ProcessResult result = sandbox.exec(
            List.of("sleep", "10"),
            ProcessOptions.builder().timeoutSeconds(1).build()
        );
        
        assertTrue(result.isTimedOut());
        assertEquals(-1, result.getExitCode());
    }

    @Test
    @DisplayName("命令输出应该被正确捕获")
    void testOutputCapture() {
        ProcessResult result = sandbox.exec(
            List.of("echo", "stdout content"),
            ProcessOptions.builder().build()
        );
        
        assertTrue(result.getStdout().contains("stdout content"));
    }

    @Test
    @DisplayName("错误输出应该被正确捕获")
    void testErrorCapture() {
        ProcessResult result = sandbox.exec(
            List.of("cat", "/nonexistent/file"),
            ProcessOptions.builder().build()
        );
        
        assertFalse(result.isSuccess());
        assertNotEquals(0, result.getExitCode());
    }
}
