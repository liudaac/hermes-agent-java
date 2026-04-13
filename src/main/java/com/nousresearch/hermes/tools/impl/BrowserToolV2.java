package com.nousresearch.hermes.tools.impl;

import com.microsoft.playwright.*;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser automation tool with Playwright integration.
 */
public class BrowserToolV2 {
    private static final Logger logger = LoggerFactory.getLogger(BrowserToolV2.class);
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private Playwright playwright;
    
    public BrowserToolV2() {
        try {
            this.playwright = Playwright.create();
            logger.info("Playwright initialized");
        } catch (Exception e) {
            logger.warn("Playwright not available: {}", e.getMessage());
        }
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolRegistry.Builder()
            .name("browser_open")
            .toolset("browser")
            .schema(Map.of("description", "Open a URL in a browser",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string"),
                        "headless", Map.of("type", "boolean", "default", true)),
                    "required", List.of("url"))))
            .handler(this::openUrl).emoji("🌐").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("browser_click").toolset("browser")
            .schema(Map.of("description", "Click an element",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string"), "selector", Map.of("type", "string")),
                    "required", List.of("session_id", "selector"))))
            .handler(this::clickElement).emoji("🖱️").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("browser_type").toolset("browser")
            .schema(Map.of("description", "Type text",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string"), "selector", Map.of("type", "string"), "text", Map.of("type", "string")),
                    "required", List.of("session_id", "selector", "text"))))
            .handler(this::typeText).emoji("⌨️").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("browser_get_content").toolset("browser")
            .schema(Map.of("description", "Get page content",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string")),
                    "required", List.of("session_id"))))
            .handler(this::getContent).emoji("📄").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("browser_close").toolset("browser")
            .schema(Map.of("description", "Close browser",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string")),
                    "required", List.of("session_id"))))
            .handler(this::closeSession).emoji("❌").build());
    }
    
    private String openUrl(Map<String, Object> args) {
        if (playwright == null) return ToolRegistry.toolError("Playwright not available");
        String url = (String) args.get("url");
        boolean headless = args.containsKey("headless") ? (Boolean) args.get("headless") : true;
        try {
            BrowserSession session = createSession(url, headless);
            sessions.put(session.id, session);
            session.page.waitForLoadState(LoadState.NETWORKIDLE);
            String title = session.page.title();
            String text = session.page.textContent("body");
            return ToolRegistry.toolResult(Map.of("session_id", session.id, "url", session.page.url(), "title", title, "content_preview", text.substring(0, Math.min(1000, text.length()))));
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed: " + e.getMessage());
        }
    }
    
    private String clickElement(Map<String, Object> args) {
        BrowserSession session = sessions.get(args.get("session_id"));
        if (session == null) return ToolRegistry.toolError("Session not found");
        try {
            session.page.click((String) args.get("selector"));
            return ToolRegistry.toolResult(Map.of("clicked", true));
        } catch (Exception e) {
            return ToolRegistry.toolError("Click failed: " + e.getMessage());
        }
    }
    
    private String typeText(Map<String, Object> args) {
        BrowserSession session = sessions.get(args.get("session_id"));
        if (session == null) return ToolRegistry.toolError("Session not found");
        try {
            session.page.fill((String) args.get("selector"), (String) args.get("text"));
            return ToolRegistry.toolResult(Map.of("typed", true));
        } catch (Exception e) {
            return ToolRegistry.toolError("Type failed: " + e.getMessage());
        }
    }
    
    private String getContent(Map<String, Object> args) {
        BrowserSession session = sessions.get(args.get("session_id"));
        if (session == null) return ToolRegistry.toolError("Session not found");
        String text = session.page.textContent("body");
        return ToolRegistry.toolResult(Map.of("content", text.substring(0, Math.min(5000, text.length()))));
    }
    
    private String closeSession(Map<String, Object> args) {
        BrowserSession session = sessions.remove(args.get("session_id"));
        if (session != null) session.close();
        return ToolRegistry.toolResult(Map.of("closed", true));
    }
    
    private BrowserSession createSession(String url, boolean headless) {
        Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
        Page page = browser.newPage();
        page.navigate(url);
        return new BrowserSession(UUID.randomUUID().toString(), browser, page);
    }
    
    record BrowserSession(String id, Browser browser, Page page) {
        void close() {
            browser.close();
        }
    }
}
