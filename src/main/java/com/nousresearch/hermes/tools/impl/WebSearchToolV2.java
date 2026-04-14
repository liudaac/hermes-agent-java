package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.impl.web.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Enhanced web search tool with multi-backend support.
 * Supports: Brave, Firecrawl, Tavily, Exa
 */
public class WebSearchToolV2 {
    private static final Logger logger = LoggerFactory.getLogger(WebSearchToolV2.class);
    
    private final List<WebSearchBackend> backends = new ArrayList<>();
    private WebSearchBackend primaryBackend;
    
    public WebSearchToolV2() {
        backends.add(new BraveBackend());
        backends.add(new TavilyBackend());
        backends.add(new ExaBackend());
        backends.add(new FirecrawlBackend());
        selectPrimaryBackend();
    }
    
    private void selectPrimaryBackend() {
        String configuredBackend = ConfigManager.getInstance().getWebBackend();
        for (WebSearchBackend backend : backends) {
            if (backend.getName().equalsIgnoreCase(configuredBackend) && backend.isAvailable()) {
                primaryBackend = backend;
                logger.info("Using configured web backend: {}", backend.getName());
                return;
            }
        }
        for (WebSearchBackend backend : backends) {
            if (backend.isAvailable()) {
                primaryBackend = backend;
                logger.info("Using available web backend: {}", backend.getName());
                return;
            }
        }
        logger.warn("No web search backend configured");
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("web_search")
            .toolset("web_search")
            .schema(Map.of(
                "description", "Search the web for information on any topic. Returns up to 5 relevant results with titles, URLs, and descriptions.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query"),
                        "count", Map.of("type", "integer", "description", "Number of results", "default", 5),
                        "backend", Map.of("type", "string", "enum", List.of("brave", "tavily", "exa", "firecrawl"))
                    ),
                    "required", List.of("query")
                )
            ))
            .handler(this::executeSearch)
            .emoji("🔍")
            .build());
        
        registry.register(new ToolEntry.Builder()
            .name("web_extract")
            .toolset("web_search")
            .schema(Map.of(
                "description", "Extract content from web page URLs. Returns page content in markdown format. For simple information retrieval from a known URL, this is the PREFERRED tool - it's faster and cheaper than browser tools. Also works with PDF URLs. If extraction fails or the page requires interaction, use browser_navigate instead.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "URL to extract content from"),
                        "backend", Map.of("type", "string", "enum", List.of("tavily", "exa", "firecrawl"))
                    ),
                    "required", List.of("url")
                )
            ))
            .handler(this::executeExtract)
            .emoji("📄")
            .build());
    }
    
    private String executeSearch(Map<String, Object> args) {
        String query = (String) args.get("query");
        int count = args.containsKey("count") ? ((Number) args.get("count")).intValue() : 5;
        String requestedBackend = (String) args.get("backend");
        
        if (query == null || query.trim().isEmpty()) {
            return ToolRegistry.toolError("Query is required");
        }
        
        try {
            WebSearchBackend backend = selectBackend(requestedBackend);
            if (backend == null) {
                return ToolRegistry.toolError("No web search backend available");
            }
            
            var result = backend.search(query, count, null);
            
            List<Map<String, Object>> formattedResults = new ArrayList<>();
            for (var item : result.results) {
                Map<String, Object> formatted = new LinkedHashMap<>();
                formatted.put("title", item.title);
                formatted.put("url", item.url);
                formatted.put("snippet", item.snippet);
                formattedResults.add(formatted);
            }
            
            return ToolRegistry.toolResult(Map.of(
                "query", result.query,
                "backend", result.backend,
                "results_count", formattedResults.size(),
                "results", formattedResults
            ));
            
        } catch (Exception e) {
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }
    
    private String executeExtract(Map<String, Object> args) {
        String url = (String) args.get("url");
        String requestedBackend = (String) args.get("backend");
        
        if (url == null || url.trim().isEmpty()) {
            return ToolRegistry.toolError("URL is required");
        }
        
        try {
            WebSearchBackend backend = selectBackendForExtract(requestedBackend);
            if (backend == null) {
                return ToolRegistry.toolError("No extraction backend available");
            }
            
            var result = backend.extract(url, null);
            
            return ToolRegistry.toolResult(Map.of(
                "url", result.url,
                "title", result.title,
                "content", result.content.substring(0, Math.min(5000, result.content.length())),
                "backend", result.backend
            ));
            
        } catch (Exception e) {
            return ToolRegistry.toolError("Extraction failed: " + e.getMessage());
        }
    }
    
    private WebSearchBackend selectBackend(String requested) {
        if (requested != null) {
            for (WebSearchBackend backend : backends) {
                if (backend.getName().equalsIgnoreCase(requested) && backend.isAvailable()) {
                    return backend;
                }
            }
        }
        return primaryBackend;
    }
    
    private WebSearchBackend selectBackendForExtract(String requested) {
        if (requested != null) {
            for (WebSearchBackend backend : backends) {
                if (backend.getName().equalsIgnoreCase(requested) && backend.isAvailable()) {
                    return backend;
                }
            }
        }
        // For extraction, prefer Tavily > Exa > Firecrawl
        for (WebSearchBackend backend : backends) {
            if (backend.getName().equals("tavily") && backend.isAvailable()) {
                return backend;
            }
        }
        for (WebSearchBackend backend : backends) {
            if (backend.getName().equals("exa") && backend.isAvailable()) {
                return backend;
            }
        }
        for (WebSearchBackend backend : backends) {
            if (backend.getName().equals("firecrawl") && backend.isAvailable()) {
                return backend;
            }
        }
        return null;
    }
}
