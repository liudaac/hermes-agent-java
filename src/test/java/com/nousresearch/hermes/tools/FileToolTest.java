package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.tools.impl.FileTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FileTool.
 */
public class FileToolTest {
    
    private FileTool fileTool;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        fileTool = new FileTool();
    }
    
    @Test
    void testReadFile() throws Exception {
        // Create test file
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello, World!");
        
        // Read via tool
        String result = fileTool.readFile(Map.of("path", testFile.toString()));
        
        assertTrue(result.contains("Hello, World!"));
    }
    
    @Test
    void testWriteFile() throws Exception {
        Path testFile = tempDir.resolve("write_test.txt");
        
        String result = fileTool.writeFile(Map.of(
            "path", testFile.toString(),
            "content", "Test content"
        ));
        
        assertTrue(Files.exists(testFile));
        assertEquals("Test content", Files.readString(testFile));
    }
    
    @Test
    void testSearchFiles() throws Exception {
        // Create test files
        Files.writeString(tempDir.resolve("file1.txt"), "apple banana");
        Files.writeString(tempDir.resolve("file2.txt"), "apple cherry");
        Files.createDirectories(tempDir.resolve("subdir"));
        Files.writeString(tempDir.resolve("subdir/file3.txt"), "banana date");
        
        String result = fileTool.searchFiles(Map.of(
            "path", tempDir.toString(),
            "regex", "apple",
            "file_pattern", "*.txt"
        ));
        
        assertTrue(result.contains("file1.txt"));
        assertTrue(result.contains("file2.txt"));
        assertFalse(result.contains("file3.txt"));
    }
    
    @Test
    void testListDirectory() throws Exception {
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createFile(tempDir.resolve("file2.txt"));
        Files.createDirectories(tempDir.resolve("folder"));
        
        String result = fileTool.listDirectory(Map.of(
            "path", tempDir.toString()
        ));
        
        assertTrue(result.contains("file1.txt"));
        assertTrue(result.contains("file2.txt"));
        assertTrue(result.contains("folder"));
    }
}
