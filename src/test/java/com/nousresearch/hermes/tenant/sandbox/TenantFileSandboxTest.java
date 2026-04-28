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
    
    @Test
    void testCleanupSessionWorkspace() throws IOException {
        String sessionId = "test-session-cleanup";
        Path workspace = sandbox.createSessionWorkspace(sessionId);
        
        assertTrue(Files.exists(workspace), "Workspace should exist before cleanup");
        
        sandbox.cleanupSessionWorkspace(sessionId);
        
        assertFalse(Files.exists(workspace), "Workspace should be deleted after cleanup");
        assertNull(sandbox.getSessionWorkspace(sessionId));
    }
    
    @Test
    void testStorageUsage() throws IOException {
        // 创建一些测试文件
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, "Hello World");
        Files.writeString(file2, "Test content here");
        
        Files.createDirectories(tempDir.resolve("subdir"));
        Files.writeString(tempDir.resolve("subdir/file3.txt"), "Nested file");
        
        TenantFileSandbox.StorageUsage usage = sandbox.getStorageUsage();
        
        assertTrue(usage.usedBytes() > 0, "Used bytes should be positive");
        assertTrue(usage.fileCount() >= 3, "Should have at least 3 files");
        assertTrue(usage.dirCount() >= 1, "Should have at least 1 subdirectory");
    }
    
    @Test
    void testForbiddenFileType() throws IOException {
        // 测试禁止的文件类型
        Path exeFile = tempDir.resolve("malicious.exe");
        Files.createFile(exeFile);
        
        TenantFileSandbox.PathValidationResult result = sandbox.validatePath(
            exeFile.toString(),
            TenantFileSandbox.AccessMode.READ
        );
        
        assertFalse(result.isAllowed(), "Executable files should be rejected");
    }
    
    @Test
    void testEmptyPath() {
        TenantFileSandbox.PathValidationResult result = sandbox.validatePath(
            "",
            TenantFileSandbox.AccessMode.READ
        );
        
        assertFalse(result.isAllowed(), "Empty path should be rejected");
    }
    
    @Test
    void testNullPath() {
        TenantFileSandbox.PathValidationResult result = sandbox.validatePath(
            null,
            TenantFileSandbox.AccessMode.READ
        );
        
        assertFalse(result.isAllowed(), "Null path should be rejected");
    }
    
    @Test
    void testCheckStorageQuota() throws IOException {
        // 创建一个大文件
        Path largeFile = tempDir.resolve("large.bin");
        byte[] data = new byte[1024]; // 1KB
        Files.write(largeFile, data);
        
        // 应该仍在配额内（默认 1GB）
        assertTrue(sandbox.checkStorageQuota(0), "Should have quota available");
        
        // 测试超过配额的情况需要修改配置，这里简化测试
    }
}
