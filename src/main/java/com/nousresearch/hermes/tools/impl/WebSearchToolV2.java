package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.plugin.PluginManager;
import com.nousresearch.hermes.plugin.registry.ProviderRegistry;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.impl.web.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Enhanced web search tool with pluggable backend support via ProviderRegistry.
 * Backends are registered as providers; the tool queries the registry at runtime.
 */
public class WebSearchToolV2 {
    private static final Logger logger = LoggerFactory.getLogger(WebSearchToolV2.class);

    private ProviderRegistry<WebSearchBackend> providerRegistry;
    private WebSearchBackend primaryBackend;

    public WebSearchToolV2() {
        this(resolveRegistry());
    }

    @SuppressWarnings("unchecked")
    private static ProviderRegistry<WebSearchBackend> resolveRegistry() {
        PluginManager pm = PluginManager.getInstance();
        if (pm != null) {
            return (ProviderRegistry<WebSearchBackend>) pm.getProviderRegistry("web_search");
        }
        return null;
    }

    public WebSearchToolV2(ProviderRegistry<WebSearchBackend> registry) {
        this.providerRegistry = registry;
        selectPrimaryBackend();
    }

    public void setProviderRegistry(ProviderRegistry<WebSearchBackend> registry) {
        this.providerRegistry = registry;
        selectPrimaryBackend();
    }

    private void selectPrimaryBackend() {
        if (providerRegistry == null) {
            logger.warn("No provider registry configured for web search");
            return;
        }
        String configuredBackend = ConfigManager.getInstance().getWebBackend();
        Optional<WebSearchBackend> match = providerRegistry.resolve(configuredBackend);
        if (match.isPresent() && match.get().isAvailable()) {
            primaryBackend = match.get();
            logger.info("Using configured web backend: {}", primaryBackend.getName());
            return;
        }
        for (WebSearchBackend backend : providerRegistry.listAll()) {
            if (backend.isAvailable()) {
                primaryBackend = backend;
                logger.info("Using available web backend: {}", backend.getName());
                return;
            }
        }
        logger.warn("No web search backend available");
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
                "description", "Extract content from web page URLs. Returns page content in markdown format. Use this for simple information retrieval from a known URL when the user does NOT explicitly ask to open a browser. If the user says 'open browser', 'visit page', or 'go to URL', use browser_navigate instead. Also works with PDF URLs. If extraction fails or the page requires interaction, use browser_navigate.",
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
        if (requested != null && providerRegistry != null) {
            Optional<WebSearchBackend> match = providerRegistry.resolve(requested);
            if (match.isPresent() && match.get().isAvailable()) {
                return match.get();
            }
        }
        return primaryBackend;
    }

    private WebSearchBackend selectBackendForExtract(String requested) {
        if (requested != null && providerRegistry != null) {
            Optional<WebSearchBackend> match = providerRegistry.resolve(requested);
            if (match.isPresent() && match.get().isAvailable()) {
                return match.get();
            }
        }
        // For extraction, prefer Tavily > Exa > Firecrawl
        for (String name : List.of("tavily", "exa", "firecrawl")) {
            WebSearchBackend b = resolveAvailable(name);
            if (b != null) return b;
        }
        return primaryBackend;
    }

    private WebSearchBackend resolveAvailable(String name) {
        if (providerRegistry == null) return null;
        Optional<WebSearchBackend> match = providerRegistry.resolve(name);
        return match.filter(WebSearchBackend::isAvailable).orElse(null);
    }
}
