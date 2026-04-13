package com.nousresearch.hermes.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * MCP (Model Context Protocol) tool gateway.
 */
public class MCPTool {
    private static final Logger logger = LoggerFactory.getLogger(MCPTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final Map<String, MCPServer> servers;
    
    public MCPTool() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        this.servers = new HashMap<>();
        
        // Load configured servers from environment
        loadServers();
    }
    
    private void loadServers() {
        // Example: MCP_SERVER_1_NAME=filesystem, MCP_SERVER_1_URL=http://localhost:3001
        for (int i = 1; i <= 10; i++) {
            String name = System.getenv("MCP_SERVER_" + i + "_NAME");
            String url = System.getenv("MCP_SERVER_" + i + "_URL");
            if (name != null && url != null) {
                servers.put(name, new MCPServer(name, url));
            }
        }
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("mcp_list_servers")
            .toolset("mcp")
            .schema(Map.of("description", "List configured MCP servers"))
            .handler(args -> listServers()).emoji("🖥️").build());
        
        registry.register(new ToolEntry.Builder()
            .name("mcp_list_tools")
            .toolset("mcp")
            .schema(Map.of("description", "List tools from an MCP server",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("server", Map.of("type", "string")),
                    "required", List.of("server"))))
            .handler(this::listTools).emoji("🔧").build());
        
        registry.register(new ToolEntry.Builder()
            .name("mcp_call")
            .toolset("mcp")
            .schema(Map.of("description", "Call an MCP tool",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "server", Map.of("type", "string"),
                        "tool", Map.of("type", "string"),
                        "arguments", Map.of("type", "object")),
                    "required", List.of("server", "tool"))))
            .handler(this::callTool).emoji("⚡").build());
        
        registry.register(new ToolEntry.Builder()
            .name("mcp_add_server")
            .toolset("mcp")
            .schema(Map.of("description", "Add an MCP server",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "url", Map.of("type", "string")),
                    "required", List.of("name", "url"))))
            .handler(this::addServer).emoji("➕").build());
    }
    
    private String listServers() {
        List<Map<String, String>> serverList = new ArrayList<>();
        for (MCPServer server : servers.values()) {
            serverList.add(Map.of("name", server.name, "url", server.url));
        }
        return ToolRegistry.toolResult(Map.of("servers", serverList));
    }
    
    private String listTools(Map<String, Object> args) {
        String serverName = (String) args.get("server");
        MCPServer server = servers.get(serverName);
        
        if (server == null) {
            return ToolRegistry.toolError("Server not found: " + serverName);
        }
        
        try {
            Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "method", "tools/list",
                "id", 1
            );
            
            Request httpRequest = new Request.Builder()
                .url(server.url)
                .post(RequestBody.create(mapper.writeValueAsString(request), JSON))
                .build();
            
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                JsonNode result = mapper.readTree(response.body().string());
                return ToolRegistry.toolResult(Map.of("tools", result.path("result").toString()));
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed: " + e.getMessage());
        }
    }
    
    private String callTool(Map<String, Object> args) {
        String serverName = (String) args.get("server");
        String toolName = (String) args.get("tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) args.getOrDefault("arguments", Map.of());
        
        MCPServer server = servers.get(serverName);
        if (server == null) {
            return ToolRegistry.toolError("Server not found: " + serverName);
        }
        
        try {
            Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments),
                "id", 1
            );
            
            Request httpRequest = new Request.Builder()
                .url(server.url)
                .post(RequestBody.create(mapper.writeValueAsString(request), JSON))
                .build();
            
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                JsonNode result = mapper.readTree(response.body().string());
                return ToolRegistry.toolResult(Map.of("result", result.path("result").toString()));
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("Call failed: " + e.getMessage());
        }
    }
    
    private String addServer(Map<String, Object> args) {
        String name = (String) args.get("name");
        String url = (String) args.get("url");
        
        servers.put(name, new MCPServer(name, url));
        return ToolRegistry.toolResult(Map.of("added", name, "url", url));
    }
    
    record MCPServer(String name, String url) {}
}
