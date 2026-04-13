package com.nousresearch.hermes.tools.impl.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Exa backend for web search and extraction.
 */
public class ExaBackend implements WebSearchBackend {
    private static final Logger logger = LoggerFactory.getLogger(ExaBackend.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE_URL = "https://api.exa.ai";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String apiKey;
    
    public ExaBackend() {
        this.apiKey = System.getenv("EXA_API_KEY");
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public SearchResult search(String query, int count, Map<String, Object> options) throws Exception {
        long startTime = System.currentTimeMillis();
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("EXA_API_KEY not set");
        }
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        requestBody.put("numResults", Math.min(count, 10));
        requestBody.put("useAutoprompt", true);
        requestBody.put("type", "neural");
        
        // Options
        if (options != null) {
            if (options.containsKey("includeDomains")) {
                requestBody.put("includeDomains", options.get("includeDomains"));
            }
            if (options.containsKey("excludeDomains")) {
                requestBody.put("excludeDomains", options.get("excludeDomains"));
            }
            if (options.containsKey("startPublishedDate")) {
                requestBody.put("startPublishedDate", options.get("startPublishedDate"));
            }
            if (options.containsKey("endPublishedDate")) {
                requestBody.put("endPublishedDate", options.get("endPublishedDate"));
            }
        }
        
        // Always request text content
        requestBody.put("contents", Map.of(
            "text", Map.of("maxCharacters", 5000)
        ));
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/search")
            .post(body)
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new Exception("Exa API error: " + response.code() + " - " + errorBody);
            }
            
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode results = root.path("results");
            
            List<SearchItem> items = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode result : results) {
                    String content = "";
                    if (result.has("text")) {
                        content = result.path("text").asText();
                    }
                    
                    items.add(new SearchItem(
                        result.path("title").asText(),
                        result.path("url").asText(),
                        content.substring(0, Math.min(500, content.length())),
                        content,
                        result.path("publishedDate").asText(null)
                    ));
                }
            }
            
            return new SearchResult(query, items, "exa", 
                                   System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public ExtractResult extract(String url, Map<String, Object> options) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("EXA_API_KEY not set");
        }
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("urls", List.of(url));
        
        Map<String, Object> contents = new HashMap<>();
        contents.put("text", Map.of("maxCharacters", 10000));
        requestBody.put("contents", contents);
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/contents")
            .post(body)
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new Exception("Exa contents error: " + response.code() + " - " + errorBody);
            }
            
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode results = root.path("results");
            
            if (results.isArray() && results.size() > 0) {
                JsonNode first = results.get(0);
                String text = first.path("text").asText();
                
                return new ExtractResult(
                    url,
                    first.path("title").asText(),
                    text,
                    text,
                    "exa"
                );
            }
            
            throw new Exception("No extraction results");
        }
    }
    
    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }
    
    @Override
    public String getName() {
        return "exa";
    }
}
