package com.nousresearch.hermes.tools.impl.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Tavily backend for web search and extraction.
 */
public class TavilyBackend implements WebSearchBackend {
    private static final Logger logger = LoggerFactory.getLogger(TavilyBackend.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE_URL = "https://api.tavily.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String apiKey;
    
    public TavilyBackend() {
        this.apiKey = System.getenv("TAVILY_API_KEY");
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public SearchResult search(String query, int count, Map<String, Object> options) throws Exception {
        long startTime = System.currentTimeMillis();
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("TAVILY_API_KEY not set");
        }
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        requestBody.put("max_results", Math.min(count, 20));
        requestBody.put("api_key", apiKey);
        
        // Options
        if (options != null) {
            if (options.containsKey("search_depth")) {
                requestBody.put("search_depth", options.get("search_depth"));
            }
            if (options.containsKey("include_answer")) {
                requestBody.put("include_answer", options.get("include_answer"));
            }
            if (options.containsKey("include_raw_content")) {
                requestBody.put("include_raw_content", options.get("include_raw_content"));
            }
        } else {
            requestBody.put("search_depth", "basic");
            requestBody.put("include_answer", false);
        }
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/search")
            .post(body)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new Exception("Tavily API error: " + response.code() + " - " + errorBody);
            }
            
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode results = root.path("results");
            
            List<SearchItem> items = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode result : results) {
                    items.add(new SearchItem(
                        result.path("title").asText(),
                        result.path("url").asText(),
                        result.path("content").asText(),
                        result.path("raw_content").asText(null),
                        result.path("published_date").asText(null)
                    ));
                }
            }
            
            return new SearchResult(query, items, "tavily", 
                                   System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public ExtractResult extract(String url, Map<String, Object> options) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("TAVILY_API_KEY not set");
        }
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("url", url);
        requestBody.put("api_key", apiKey);
        
        if (options != null) {
            if (options.containsKey("extract_depth")) {
                requestBody.put("extract_depth", options.get("extract_depth"));
            }
            if (options.containsKey("include_images")) {
                requestBody.put("include_images", options.get("include_images"));
            }
        }
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/extract")
            .post(body)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new Exception("Tavily extract error: " + response.code() + " - " + errorBody);
            }
            
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode data = root.path("data");
            
            if (data.isArray() && data.size() > 0) {
                JsonNode first = data.get(0);
                return new ExtractResult(
                    url,
                    first.path("title").asText(),
                    first.path("raw_content").asText(),
                    first.path("content").asText(),
                    "tavily"
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
        return "tavily";
    }
}
