package com.nousresearch.hermes;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tool registry.
 */
public class ToolRegistryTest {
    
    private ToolRegistry registry;
    
    @BeforeEach
    void setUp() {
        // Get fresh instance for each test
        registry = ToolRegistry.getInstance();
    }
    
    @Test
    void testRegisterTool() {
        registry.register(new ToolEntry.Builder()
            .name("test_tool")
            .toolset("test")
            .schema(Map.of("description", "A test tool"))
            .handler(args -> "{\"result\": \"ok\"}")
            .emoji("🧪")
            .build());
        
        assertTrue(registry.getAllToolNames().contains("test_tool"));
        assertEquals("test", registry.getToolsetForTool("test_tool"));
        assertEquals("🧪", registry.getEmoji("test_tool", "⚡"));
    }
    
    @Test
    void testDispatchTool() {
        registry.register(new ToolEntry.Builder()
            .name("echo")
            .toolset("test")
            .schema(Map.of("description", "Echo tool"))
            .handler(args -> {
                String msg = (String) args.getOrDefault("message", "hello");
                return "{\"echo\": \"" + msg + "\"}";
            })
            .build());
        
        String result = registry.dispatch("echo", Map.of("message", "world"));
        assertTrue(result.contains("world"));
    }
    
    @Test
    void testDispatchUnknownTool() {
        String result = registry.dispatch("nonexistent", Map.of());
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown tool"));
    }
    
    @Test
    void testGetDefinitions() {
        registry.register(new ToolEntry.Builder()
            .name("tool1")
            .toolset("set1")
            .schema(Map.of("description", "Tool 1"))
            .handler(args -> "{}")
            .build());
        
        registry.register(new ToolEntry.Builder()
            .name("tool2")
            .toolset("set1")
            .schema(Map.of("description", "Tool 2"))
            .handler(args -> "{}")
            .build());
        
        var definitions = registry.getDefinitions(Set.of("tool1", "tool2"), true);
        assertEquals(2, definitions.size());
    }
    
    @Test
    void testToolHelpers() {
        String error = ToolRegistry.toolError("Something went wrong");
        assertTrue(error.contains("error"));
        assertTrue(error.contains("Something went wrong"));
        
        String result = ToolRegistry.toolResult(Map.of("success", true, "data", "value"));
        assertTrue(result.contains("success"));
        assertTrue(result.contains("data"));
    }
}
