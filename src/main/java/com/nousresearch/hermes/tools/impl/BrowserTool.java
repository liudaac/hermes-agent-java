package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Browser automation tools.
 * Open URLs, take snapshots, extract content.
 */
public class BrowserTool {
    private static final Logger logger = LoggerFactory.getLogger(BrowserTool.class);
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build();
    
    // Simple in-memory page cache
    private static final Map<String, PageSnapshot> pageCache = new HashMap<>();
    private static final int MAX_CACHE_SIZE = 10;
    
    /**
     * Register browser tools.
     */
    public static void register(ToolRegistry registry) {
        // browser_open
        registry.register(new ToolEntry.Builder()
            .name("browser_open")
            .toolset("browser")
            .schema(Map.of(
                "description", "Open a URL and return the page content",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of(
                            "type", "string",
                            "description", "URL to open"
                        )
                    ),
                    "required", List.of("url")
                )
            ))
            .handler(BrowserTool::openUrl)
            .emoji("🌐")
            .build());
        
        // browser_find
        registry.register(new ToolEntry.Builder()
            .name("browser_find")
            .toolset("browser")
            .schema(Map.of(
                "description", "Find elements on the current page using CSS selector",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "selector", Map.of(
                            "type", "string",
                            "description", "CSS selector"
                        ),
                        "url", Map.of(
                            "type", "string",
                            "description", "URL (optional, uses last opened if not provided)"
                        )
                    ),
                    "required", List.of("selector")
                )
            ))
            .handler(BrowserTool::findElements)
            .emoji("🔎")
            .build());
        
        // browser_click
        registry.register(new ToolEntry.Builder()
            .name("browser_click")
            .toolset("browser")
            .schema(Map.of(
                "description", "Click an element on the page",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "selector", Map.of(
                            "type", "string",
                            "description", "CSS selector for element to click"
                        ),
                        "url", Map.of(
                            "type", "string",
                            "description", "URL (optional)"
                        )
                    ),
                    "required", List.of("selector")
                )
            ))
            .handler(BrowserTool::clickElement)
            .emoji("🖱️")
            .build());
        
        // browser_scroll
        registry.register(new ToolEntry.Builder()
            .name("browser_scroll")
            .toolset("browser")
            .schema(Map.of(
                "description", "Scroll the page",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "direction", Map.of(
                            "type", "string",
                            "description", "up, down, or to a selector",
                            "enum", List.of("up", "down", "to")
                        ),
                        "selector", Map.of(
                            "type", "string",
                            "description", "Element to scroll to (if direction=to)"
                        )
                    ),
                    "required", List.of("direction")
                )
            ))
            .handler(BrowserTool::scrollPage)
            .emoji("📜")
            .build());
    }
    
    /**
     * Open a URL.
     */
    private static String openUrl(Map<String, Object> args) {
        String url = (String) args.get("url");
        
        if (url == null || url.trim().isEmpty()) {
            return ToolRegistry.toolError("URL is required");
        }
        
        // Normalize URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        
        try {
            logger.info("Opening URL: {}", url);
            
            Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return ToolRegistry.toolError("HTTP error: " + response.code());
                }
                
                String html = response.body().string();
                Document doc = Jsoup.parse(html, url);
                
                // Cache the page
                cachePage(url, doc);
                
                // Extract useful information
                String title = doc.title();
                String text = extractReadableText(doc);
                List<Map<String, String>> links = extractLinks(doc);
                
                // Truncate text if too long
                if (text.length() > 10000) {
                    text = text.substring(0, 10000) + "\n... [truncated]";
                }
                
                return ToolRegistry.toolResult(Map.of(
                    "url", url,
                    "title", title,
                    "content", text,
                    "links_count", links.size(),
                    "links_sample", links.subList(0, Math.min(10, links.size()))
                ));
            }
            
        } catch (Exception e) {
            logger.error("Failed to open URL: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Failed to open URL: " + e.getMessage());
        }
    }
    
    /**
     * Find elements by CSS selector.
     */
    private static String findElements(Map<String, Object> args) {
        String selector = (String) args.get("selector");
        String url = (String) args.get("url");
        
        if (selector == null || selector.trim().isEmpty()) {
            return ToolRegistry.toolError("Selector is required");
        }
        
        try {
            Document doc;
            if (url != null && !url.isEmpty()) {
                // Fetch the page
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                
                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    doc = Jsoup.parse(response.body().string(), url);
                    cachePage(url, doc);
                }
            } else {
                // Use cached page
                PageSnapshot snapshot = getLastCachedPage();
                if (snapshot == null) {
                    return ToolRegistry.toolError("No page loaded. Use browser_open first.");
                }
                doc = snapshot.document;
                url = snapshot.url;
            }
            
            Elements elements = doc.select(selector);
            
            List<Map<String, String>> results = new ArrayList<>();
            for (Element el : elements) {
                Map<String, String> item = new HashMap<>();
                item.put("tag", el.tagName());
                item.put("text", el.text());
                item.put("html", el.html().substring(0, Math.min(500, el.html().length())));
                if (el.hasAttr("href")) {
                    item.put("href", el.attr("href"));
                }
                results.add(item);
            }
            
            return ToolRegistry.toolResult(Map.of(
                "url", url,
                "selector", selector,
                "count", results.size(),
                "elements", results.subList(0, Math.min(20, results.size()))
            ));
            
        } catch (Exception e) {
            logger.error("Failed to find elements: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Find failed: " + e.getMessage());
        }
    }
    
    /**
     * Click element (simulated - just returns element info).
     */
    private static String clickElement(Map<String, Object> args) {
        String selector = (String) args.get("selector");
        
        if (selector == null || selector.trim().isEmpty()) {
            return ToolRegistry.toolError("Selector is required");
        }
        
        // In a real implementation with Playwright/Selenium,
        // this would actually click the element
        // For now, we just return what would be clicked
        
        return ToolRegistry.toolResult(Map.of(
            "action", "click",
            "selector", selector,
            "note", "Click simulation - in full implementation, this would use Playwright/Selenium"
        ));
    }
    
    /**
     * Scroll page (simulated).
     */
    private static String scrollPage(Map<String, Object> args) {
        String direction = (String) args.get("direction");
        String selector = (String) args.get("selector");
        
        return ToolRegistry.toolResult(Map.of(
            "action", "scroll",
            "direction", direction,
            "selector", selector,
            "note", "Scroll simulation - in full implementation, this would use Playwright/Selenium"
        ));
    }
    
    // Helper methods
    private static void cachePage(String url, Document doc) {
        if (pageCache.size() >= MAX_CACHE_SIZE) {
            // Remove oldest entry
            String oldest = pageCache.keySet().iterator().next();
            pageCache.remove(oldest);
        }
        pageCache.put(url, new PageSnapshot(url, doc));
    }
    
    private static PageSnapshot getLastCachedPage() {
        if (pageCache.isEmpty()) {
            return null;
        }
        // Return the most recently added
        String lastKey = null;
        for (String key : pageCache.keySet()) {
            lastKey = key;
        }
        return lastKey != null ? pageCache.get(lastKey) : null;
    }
    
    private static String extractReadableText(Document doc) {
        // Remove script and style elements
        doc.select("script, style, nav, footer, header").remove();
        
        // Get text from main content areas
        Elements contentSelectors = doc.select("main, article, .content, #content, .main");
        if (!contentSelectors.isEmpty()) {
            return contentSelectors.first().text();
        }
        
        // Fallback to body text
        return doc.body().text();
    }
    
    private static List<Map<String, String>> extractLinks(Document doc) {
        List<Map<String, String>> links = new ArrayList<>();
        for (Element link : doc.select("a[href]")) {
            Map<String, String> item = new HashMap<>();
            item.put("text", link.text());
            item.put("href", link.attr("abs:href"));
            links.add(item);
        }
        return links;
    }
    
    private static class PageSnapshot {
        final String url;
        final Document document;
        final long timestamp;
        
        PageSnapshot(String url, Document document) {
            this.url = url;
            this.document = document;
            this.timestamp = System.currentTimeMillis();
        }
    }
}