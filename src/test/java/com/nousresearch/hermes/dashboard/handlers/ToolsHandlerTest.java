package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolsHandlerTest {

    @Test
    @DisplayName("ToolsHandler should build tool groups from ToolRegistry entries")
    void buildsToolGroupsFromRegistry() {
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.register(new ToolEntry.Builder()
            .name("dashboard_test_alpha")
            .toolset("dashboard_test")
            .schema(Map.of("description", "Alpha test tool"))
            .description("Alpha description")
            .emoji("🧪")
            .handler(args -> "{}")
            .build());
        registry.register(new ToolEntry.Builder()
            .name("dashboard_test_beta")
            .toolset("dashboard_test")
            .schema(Map.of("description", "Beta test tool"))
            .handler(args -> "{}")
            .build());

        ToolsHandler handler = new ToolsHandler(registry);
        List<Map<String, Object>> groups = handler.buildToolGroups();

        Map<String, Object> group = groups.stream()
            .filter(item -> "dashboard_test".equals(item.get("name")))
            .findFirst()
            .orElseThrow();

        assertEquals("ToolRegistry", group.get("source"));
        assertEquals(List.of("dashboard_test_alpha", "dashboard_test_beta"), group.get("tools"));
        assertEquals("🧪", group.get("emoji"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) group.get("tool_details");
        assertTrue(details.stream().anyMatch(tool -> "dashboard_test_alpha".equals(tool.get("name"))
            && "Alpha description".equals(tool.get("description"))));
    }

    @Test
    @DisplayName("ToolsHandler should build toolsets from ToolRegistry availability")
    void buildsToolsetsFromRegistry() {
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.register(new ToolEntry.Builder()
            .name("dashboard_test_gamma")
            .toolset("dashboard_test_toolset")
            .schema(Map.of("description", "Gamma test tool"))
            .handler(args -> "{}")
            .build());

        ToolsHandler handler = new ToolsHandler(registry);
        List<Map<String, Object>> toolsets = handler.buildToolsets();

        Map<String, Object> toolset = toolsets.stream()
            .filter(item -> "dashboard_test_toolset".equals(item.get("name")))
            .findFirst()
            .orElseThrow();

        assertEquals("ToolRegistry", toolset.get("source"));
        assertEquals(true, toolset.get("available"));
        assertEquals(List.of("dashboard_test_gamma"), toolset.get("tools"));
    }
}
