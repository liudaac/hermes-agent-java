package com.nousresearch.hermes.tools.impl.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Brave Search backend.
 */
public class BraveBackend implements WebSearchBackend {
    private static final Logger logger = LoggerFactory.getLogger(BraveBackend.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE_URL = "https://api.search.brave.com/res/v1";
    
    private final OkHttpClient httpClient;
    private final String apiKey;
    
    public BraveBackend() {
        this.apiKey = System.getenv("BRAVE_API_KEY");
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public SearchResult search(String query, int count, Map<String, Object> options) throws Exception {
        long startTime = System.currentTimeMillis();
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("BRAVE_API_KEY not set");
        }
        
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = BASE_URL + "/web/search?q=" + encodedQuery + "&count=" + Math.min(count, 20);
        
        // Add options
        if (options != null) {
            if (options.containsKey("offset")) {
                url += "&offset=" + options.get("offset");
            }
            if (options.containsKey("freshness")) {
                url += "&freshness=" + options.get("freshness");
            }
        }
        
        Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new Exception("Brave API error: " + response.code() + " - " + errorBody);
            }
            
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode webResults = root.path("web").path("results");
            
            List<SearchItem> items = new ArrayList<>();
            if (webResults.isArray()) {
                for (JsonNode result : webResults) {
                    items.add(new SearchItem(
                        result.path("title").asText(),
                        result.path("url").asText(),
                        result.path("description").asText(),
                        null,
                        result.path("age").asText()
                    ));
                }
            }
            
            return new SearchResult(query, items, "brave", 
                                   System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public ExtractResult extract(String url, Map<String, Object> options) throws Exception {
        // Brave doesn't have extraction, fetch directly
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP error: " + response.code());
            }
            
            String html = response.body().string();
            String title = extractTitle(html);
            String text = extractText(html);
            
            return new ExtractResult(url, title, html, text, "brave-fetch");
        }
    }
    
    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }
    
    @Override
    public String getName() {
        return "brave";
    }
    
    private String extractTitle(String html) {
        int start = html.indexOf("<title>");
        int end = html.indexOf("</title>");
        if (start != -1 && end != -1) {
            return html.substring(start + 7, end).trim();
        }
        return "";
    }
    
    private String extractText(String html) {
        // Simple HTML stripping
        return html
            .replaceAll("<script[^>]*>.*?</script>", "")
            .replaceAll("<style[^>]*>.*?</style>", "")
            .replaceAll("<[^>]+>", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
