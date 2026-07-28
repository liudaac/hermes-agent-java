package com.nousresearch.hermes.tenant.sandbox;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TenantFileSandbox
 */
public class TenantFileSandboxTest {
    
    private TenantFileSandbox sandbox;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        FileSandboxConfig config = FileSandboxConfig.defaults();
        sandbox = new TenantFileSandbox("test-tenant", tempDir, config);
    }
    
    @Test
    void testPathValidation_AllowedPath() throws IOException {
        // 创建测试文件在沙箱内
        Path testFile = tempDir.resolve("workspace/test.txt");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "test content");
        
        // 测试沙箱内的路径
        TenantFileSandbox.PathValidationResult result = sandbox.validatePath(
            testFile.toString(),
            TenantFileSandbox.AccessMode.READ
        );
        
        assertTrue(result.isAllowed(), "Path within sandbox should be allowed");
    }
    
    @Test
    void testPathValidation_PathTraversal() {
        // 测试路径遍历攻击
        TenantFileSandbox.PathValidationResult result = sandbox.validatePath(
            "../../../etc/passwd",
            TenantFileSandbox.AccessMode.READ
        );
        
        assertFalse(result.isAllowed(), "Path traversal should be rejected");
        assertNotNull(result.getReason());
        assertTrue(result.getReason().contains("traversal") || result.getReason().contains("outside"));
    }
    
    @Test
    void testPathValidation_SymlinkNotAllowed() throws IOException {
        // 创建符号链接
        Path target = tempDir.resolve("target.txt");
        Path link = tempDir.resolve("link.txt");
        Files.createFile(target);
        Files.createSymbolicLink(link, target);
        
        FileSandboxConfig config = new FileSandboxConfig();
        config.setAllowSymlinks(false);
        TenantFileSandbox strictSandbox = new TenantFileSandbox("test", tempDir, config);
        
        TenantFileSandbox.PathValidationResult result = strictSandbox.validatePath(
            link.toString(),
            TenantFileSandbox.AccessMode.READ
        );
        
        assertFalse(result.isAllowed(), "Symlinks should be rejected when disabled");
    }
    
    @Test
    void testCreateSessionWorkspace() throws IOException {
        String sessionId = "test-session-123";
        Path workspace = sandbox.createSessionWorkspace(sessionId);
        
        assertTrue(Files.exists(workspace), "Workspace should be created");
        assertTrue(Files.exists(workspace.resolve("uploads")), "Uploads dir should exist");
        assertTrue(Files.exists(workspace.resolve("output")), "Output dir should exist");
        assertTrue(Files.exists(workspace.resolve("temp")), "Temp dir should exist");
        
        // 验证可以获取
        Path retrieved = sandbox.getSessionWorkspace(sessionId);
        assertEquals(workspace, retrieved);
    }
    
}
