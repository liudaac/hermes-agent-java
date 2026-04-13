package com.nousresearch.hermes.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tools.ToolRegistry;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Home Assistant integration tool.
 */
public class HomeAssistantTool {
    private static final Logger logger = LoggerFactory.getLogger(HomeAssistantTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String token;
    
    public HomeAssistantTool() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        
        this.baseUrl = System.getenv("HA_URL");
        this.token = System.getenv("HA_TOKEN");
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolRegistry.Builder()
            .name("ha_state")
            .toolset("homeassistant")
            .schema(Map.of("description", "Get entity state",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("entity_id", Map.of("type", "string")),
                    "required", List.of("entity_id"))))
            .handler(this::getState).emoji("📊").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("ha_turn_on")
            .toolset("homeassistant")
            .schema(Map.of("description", "Turn on an entity",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("entity_id", Map.of("type", "string")),
                    "required", List.of("entity_id"))))
            .handler(args -> callService("turn_on", args)).emoji("💡").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("ha_turn_off")
            .toolset("homeassistant")
            .schema(Map.of("description", "Turn off an entity",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("entity_id", Map.of("type", "string")),
                    "required", List.of("entity_id"))))
            .handler(args -> callService("turn_off", args)).emoji("🌑").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("ha_set_temperature")
            .toolset("homeassistant")
            .schema(Map.of("description", "Set climate temperature",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "entity_id", Map.of("type", "string"),
                        "temperature", Map.of("type", "number")),
                    "required", List.of("entity_id", "temperature"))))
            .handler(this::setTemperature).emoji("🌡️").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("ha_states")
            .toolset("homeassistant")
            .schema(Map.of("description", "List all entity states"))
            .handler(args -> listStates()).emoji("📋").build());
    }
    
    private String getState(Map<String, Object> args) {
        String entityId = (String) args.get("entity_id");
        
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/api/states/" + entityId)
                .header("Authorization", "Bearer " + token)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return ToolRegistry.toolError("Failed: " + response.code());
                }
                
                JsonNode state = mapper.readTree(response.body().string());
                return ToolRegistry.toolResult(Map.of(
                    "entity_id", state.path("entity_id").asText(),
                    "state", state.path("state").asText(),
                    "attributes", state.path("attributes").toString()
                ));
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("Error: " + e.getMessage());
        }
    }
    
    private String callService(String service, Map<String, Object> args) {
        String entityId = (String) args.get("entity_id");
        String domain = entityId.split("\\.")[0];
        
        try {
            Map<String, Object> body = Map.of("entity_id", entityId);
            
            Request request = new Request.Builder()
                .url(baseUrl + "/api/services/" + domain + "/" + service)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .header("Authorization", "Bearer " + token)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return ToolRegistry.toolResult(Map.of("success", response.isSuccessful()));
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("Error: " + e.getMessage());
        }
    }
    
    private String setTemperature(Map<String, Object> args) {
        String entityId = (String) args.get("entity_id");
        double temperature = ((Number) args.get("temperature")).doubleValue();
        
        try {
            Map<String, Object> body = Map.of(
                "entity_id", entityId,
                "temperature", temperature
            );
            
            Request request = new Request.Builder()
                .url(baseUrl + "/api/services/climate/set_temperature")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .header("Authorization", "Bearer " + token)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return ToolRegistry.toolResult(Map.of("temperature_set", temperature));
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("Error: " + e.getMessage());
        }
    }
    
    private String listStates() {
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/api/states")
                .header("Authorization", "Bearer " + token)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                JsonNode states = mapper.readTree(response.body().string());
                
                List<Map<String, String>> entities = new ArrayList<>();
                for (JsonNode state : states) {
                    entities.add(Map.of(
                        "entity_id", state.path("entity_id").asText(),
                        "state", state.path("state").asText()
                    ));
                }
                
                return ToolRegistry.toolResult(Map.of("entities", entities, "count", entities.size()));
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("Error: " + e.getMessage());
        }
    }
}
