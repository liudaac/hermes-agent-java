package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Web search tool implementation.
 * Uses Brave Search API or DuckDuckGo as fallback.
 */
public class WebSearchTool {
    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build();
    
    /**
     * Register web search tools.
     */
    public static void register(ToolRegistry registry) {
        // web_search tool
        registry.register(new ToolRegistry.Builder()
            .name("web_search")
            .toolset("web_search")
            .schema(Map.of(
                "description", "Search the web for information",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of(
                            "type", "string",
                            "description", "Search query"
                        ),
                        "count", Map.of(
                            "type", "integer",
                            "description", "Number of results (1-10)",
                            "default", 5
                        )
                    ),
                    "required", List.of("query")
                )
            ))
            .handler(WebSearchTool::executeSearch)
            .emoji("🔍")
            .build());
        
        // web_extract tool
        registry.register(new ToolRegistry.Builder()
            .name("web_extract")
            .toolset("web_search")
            .schema(Map.of(
                "description", "Extract content from a web page",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of(
                            "type", "string",
                            "description", "URL to extract"
                        )
                    ),
                    "required", List.of("url")
                )
            ))
            .handler(WebSearchTool::executeExtract)
            .emoji("📄")
            .build());
    }
    
    /**
     * Execute web search.
     */
    private static String executeSearch(Map<String, Object> args) {
        String query = (String) args.get("query");
        int count = args.containsKey("count") ? ((Number) args.get("count")).intValue() : 5;
        
        if (query == null || query.trim().isEmpty()) {
            return ToolRegistry.toolError("Query is required");
        }
        
        try {
            // Check for Brave API key
            String braveApiKey = System.getenv("BRAVE_API_KEY");
            
            if (braveApiKey != null && !braveApiKey.isEmpty()) {
                return searchWithBrave(query, count, braveApiKey);
            } else {
                return searchWithDuckDuckGo(query, count);
            }
            
        } catch (Exception e) {
            logger.error("Web search failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }
    
    /**
     * Search using Brave API.
     */
    private static String searchWithBrave(String query, int count, String apiKey) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.search.brave.com/res/v1/web/search?q=" + encodedQuery + 
                     "&count=" + Math.min(count, 10);
        
        Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return ToolRegistry.toolError("Brave API error: " + response.code());
            }
            
            String body = response.body().string();
            // Parse and format results
            return ToolRegistry.toolResult(Map.of(
                "query", query,
                "results", body,
                "source", "brave"
            ));
        }
    }
    
    /**
     * Search using DuckDuckGo (fallback).
     */
    private static String searchWithDuckDuckGo(String query, int count) throws Exception {
        // DuckDuckGo doesn't have a public API, so we'd need to use
        // a third-party service or scrape. For now, return a placeholder.
        logger.warn("DuckDuckGo search not implemented, using placeholder");
        
        return ToolRegistry.toolResult(Map.of(
            "query", query,
            "results", List.of(),
            "source", "duckduckgo",
            "note", "DuckDuckGo search requires additional implementation"
        ));
    }
    
    /**
     * Execute web page extraction.
     */
    private static String executeExtract(Map<String, Object> args) {
        String url = (String) args.get("url");
        
        if (url == null || url.trim().isEmpty()) {
            return ToolRegistry.toolError("URL is required");
        }
        
        try {
            Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "HermesAgent/0.1.0")
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return ToolRegistry.toolError("HTTP error: " + response.code());
                }
                
                String content = response.body().string();
                
                // Simple HTML stripping (in production, use a proper HTML parser)
                String text = content
                    .replaceAll("<script[^>]*>.*?</script>", "")
                    .replaceAll("<style[^>]*>.*?</style>", "")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
                
                // Truncate if too long
                int maxLength = 10000;
                if (text.length() > maxLength) {
                    text = text.substring(0, maxLength) + "... [truncated]";
                }
                
                return ToolRegistry.toolResult(Map.of(
                    "url", url,
                    "title", extractTitle(content),
                    "content", text
                ));
            }
            
        } catch (Exception e) {
            logger.error("Web extraction failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Extraction failed: " + e.getMessage());
        }
    }
    
    /**
     * Extract title from HTML.
     */
    private static String extractTitle(String html) {
        int start = html.indexOf("<title>");
        int end = html.indexOf("</title>");
        if (start != -1 && end != -1) {
            return html.substring(start + 7, end).trim();
        }
        return "";
    }
}
