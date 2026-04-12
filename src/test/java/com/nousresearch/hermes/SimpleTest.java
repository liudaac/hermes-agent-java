package com.nousresearch.hermes;

import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.ToolEntry;

import org.junit.jupiter.api.Test;
import java.util.Map;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SimpleTest {
    @Test
    void testToolRegistry() {
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.register(new ToolEntry.Builder()
            .name("test")
            .toolset("test")
            .schema(Map.of("description", "Test tool: "