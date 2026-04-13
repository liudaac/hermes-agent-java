package com.nousresearch.hermes.tools.impl.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Firecrawl backend for web search and extraction.
 * Supports both direct API and managed gateway.
 */
public class FirecrawlBackend implements WebSearchBackend {
    private static final Logger logger = LoggerFactory.getLogger(FirecrawlBackend.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final boolean isManagedGateway;
    
    public FirecrawlBackend() {
        this.apiKey = System.getenv("FIRECRAWL_API_KEY");
        String apiUrl = System.getenv("FIRECRAWL_API_URL");
        String gatewayUrl = System.getenv("FIRECRAWL_GATEWAY_URL");
        String gatewayDomain = System.getenv("TOOL_GATEWAY_DOMAIN");
        String nousToken = System.getenv("NOUS_ACCESS_TOKEN");
        
        // Determine configuration
        if (apiKey != null && !apiKey.isEmpty()) {
            // Direct Firecrawl
            this.baseUrl = apiUrl != null ? apiUrl : "https://api.firecrawl.dev/v1";
            this.isManagedGateway = false;
        } else if (gatewayUrl != null && !gatewayUrl.isEmpty()) {
            // Managed gateway with explicit URL
            this.baseUrl = gatewayUrl;
            this.isManagedGateway = true;
        } else if (gatewayDomain != null && !gatewayDomain.isEmpty() && nousToken != null) {
            // Managed gateway from domain
            this.baseUrl = "https://firecrawl-gateway." + gatewayDomain;
            this.isManagedGateway = true;
        } else {
            // Default
            this.baseUrl = "https://api.firecrawl.dev/v1";
            this.isManagedGateway = false;
        }
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        
        logger.info("Firecrawl backend initialized: {} (managed: {})", 
                   maskUrl(baseUrl), isManagedGateway);
    }
    
    @Override
    public SearchResult search(String query, int count, Map<String, Object> options) throws Exception {
        long startTime = System.currentTimeMillis();
        
        // Firecrawl doesn't have native search, so we use a search API
        // For now, return empty result - in production, integrate with a search provider
        logger.warn("Firecrawl search not implemented, returning empty results");
        
        return new SearchResult(query, List.of(), "firecrawl", 
                               System.currentTimeMillis() - startTime);
    }
    
    @Override
    public ExtractResult extract(String url, Map<String, Object> options) throws Exception {
        String endpoint = baseUrl + "/scrape";
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("url", url);
        requestBody.put("formats", List.of("markdown", "html"));
        
        // Add options
        if (options != null) {
            if (options.containsKey("includeTags")) {
                requestBody.put("includeTags", options.get("includeTags"));
            }
            if (options.containsKey("excludeTags")) {
                requestBody.put("excludeTags", options.get("excludeTags"));
            }
            if (options.containsKey("onlyMainContent")) {
                requestBody.put("onlyMainContent", options.get("onlyMainContent"));
            }
        }
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request.Builder requestBuilder = new Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Content-Type", "application/json");
        
        // Add auth
        if (isManagedGateway) {
            String nousToken = System.getenv("NOUS_ACCESS_TOKEN");
            if (nousToken != null) {
                requestBuilder.header("Authorization", "Bearer " + nousToken);
            }
        } else if (apiKey != null) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new Exception("Firecrawl API error: " + response.code() + " - " + errorBody);
            }
            
            JsonNode root = mapper.readTree(response.body().string());
            
            if (!root.path("success").asBoolean()) {
                throw new Exception("Firecrawl scrape failed: " + root.path("error").asText());
            }
            
            JsonNode data = root.path("data");
            String title = data.path("metadata").path("title").asText();
            String markdown = data.path("markdown").asText();
            String html = data.path("html").asText();
            
            return new ExtractResult(url, title, html, markdown, "firecrawl");
        }
    }
    
    @Override
    public boolean isAvailable() {
        return apiKey != null || isManagedGateway;
    }
    
    @Override
    public String getName() {
        return "firecrawl";
    }
    
    private String maskUrl(String url) {
        if (url == null) return "null";
        if (url.contains("api.firecrawl.dev")) return "firecrawl-cloud";
        if (url.contains("gateway")) return "firecrawl-managed";
        return "firecrawl-custom";
    }
}
