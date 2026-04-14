package com.nousresearch.hermes.tools.impl;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser automation tool with Playwright integration.
 */
public class BrowserToolV2 {
    private static final Logger logger = LoggerFactory.getLogger(BrowserToolV2.class);
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private Playwright playwright;
    
    // Screenshot storage directory
    private static final Path SCREENSHOTS_DIR = Paths.get(System.getProperty("user.home"), ".hermes", "cache", "screenshots");
    
    public BrowserToolV2() {
        try {
            this.playwright = Playwright.create();
            // Ensure screenshots directory exists
            Files.createDirectories(SCREENSHOTS_DIR);
            logger.info("Playwright initialized, screenshots dir: {}", SCREENSHOTS_DIR);
        } catch (Exception e) {
            logger.warn("Playwright not available: {}", e.getMessage());
        }
    }
    
    public static void register(ToolRegistry registry) {
        BrowserToolV2 instance = new BrowserToolV2();
        instance.registerInstance(registry);
    }
    
    public void registerInstance(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("browser_open")
            .toolset("browser")
            .schema(Map.of("description", "Open a URL in a browser",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string"),
                        "headless", Map.of("type", "boolean", "default", true)),
                    "required", List.of("url"))))
            .handler(this::openUrl).emoji("🌐").build());
        
        registry.register(new ToolEntry.Builder()
            .name("browser_click").toolset("browser")
            .schema(Map.of("description", "Click an element",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string"), "selector", Map.of("type", "string")),
                    "required", List.of("session_id", "selector"))))
            .handler(this::clickElement).emoji("🖱️").build());
        
        registry.register(new ToolEntry.Builder()
            .name("browser_type").toolset("browser")
            .schema(Map.of("description", "Type text",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string"), "selector", Map.of("type", "string"), "text", Map.of("type", "string")),
                    "required", List.of("session_id", "selector", "text"))))
            .handler(this::typeText).emoji("⌨️").build());
        
        registry.register(new ToolEntry.Builder()
            .name("browser_get_content").toolset("browser")
            .schema(Map.of("description", "Get page content",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string")),
                    "required", List.of("session_id"))))
            .handler(this::getContent).emoji("📄").build());
        
        registry.register(new ToolEntry.Builder()
            .name("browser_close").toolset("browser")
            .schema(Map.of("description", "Close browser",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string")),
                    "required", List.of("session_id"))))
            .handler(this::closeSession).emoji("❌").build());
        
        // Screenshot tool - aligned with original Hermes browser_vision
        registry.register(new ToolEntry.Builder()
            .name("browser_screenshot").toolset("browser")
            .schema(Map.of("description", "Take a screenshot of the current page",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "session_id", Map.of("type", "string", "description", "Browser session ID"),
                        "full_page", Map.of("type", "boolean", "default", true, "description", "Capture full page or just viewport"),
                        "annotate", Map.of("type", "boolean", "default", false, "description", "Annotate interactive elements with numbers")
                    ),
                    "required", List.of("session_id"))))
            .handler(this::takeScreenshot).emoji("📸").build());
        
        // Navigate tool - aligned with original Hermes browser_navigate
        registry.register(new ToolEntry.Builder()
            .name("browser_navigate").toolset("browser")
            .schema(Map.of("description", "Navigate to a URL and return page snapshot with interactive elements",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "URL to navigate to"),
                        "headless", Map.of("type", "boolean", "default", true)),
                    "required", List.of("url"))))
            .handler(this::navigate).emoji("🧭").build());
        
        // Snapshot tool - aligned with original Hermes browser_snapshot
        registry.register(new ToolEntry.Builder()
            .name("browser_snapshot").toolset("browser")
            .schema(Map.of("description", "Get accessibility tree snapshot of current page with element refs",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "session_id", Map.of("type", "string", "description", "Browser session ID"),
                        "full", Map.of("type", "boolean", "default", false, "description", "Full snapshot or compact view")),
                    "required", List.of("session_id"))))
            .handler(this::getSnapshot).emoji("📋").build());
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
    
    /**
     * Navigate to URL with snapshot - aligned with original Hermes browser_navigate.
     */
    private String navigate(Map<String, Object> args) {
        if (playwright == null) return ToolRegistry.toolError("Playwright not available");
        String url = (String) args.get("url");
        boolean headless = args.containsKey("headless") ? (Boolean) args.get("headless") : true;
        try {
            BrowserSession session = createSession(url, headless);
            sessions.put(session.id, session);
            session.page.waitForLoadState(LoadState.NETWORKIDLE);
            
            String title = session.page.title();
            String snapshot = generateAccessibilitySnapshot(session.page, false);
            
            return ToolRegistry.toolResult(Map.of(
                "session_id", session.id,
                "url", session.page.url(),
                "title", title,
                "snapshot", snapshot
            ));
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed to navigate: " + e.getMessage());
        }
    }
    
    /**
     * Get accessibility snapshot - aligned with original Hermes browser_snapshot.
     */
    private String getSnapshot(Map<String, Object> args) {
        BrowserSession session = sessions.get(args.get("session_id"));
        if (session == null) return ToolRegistry.toolError("Session not found");
        
        boolean full = args.containsKey("full") ? (Boolean) args.get("full") : false;
        
        try {
            String snapshot = generateAccessibilitySnapshot(session.page, full);
            int elementCount = countInteractiveElements(session.page);
            
            return ToolRegistry.toolResult(Map.of(
                "success", true,
                "snapshot", snapshot,
                "element_count", elementCount,
                "url", session.page.url()
            ));
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed to get snapshot: " + e.getMessage());
        }
    }
    
    /**
     * Take screenshot - aligned with original Hermes browser_vision.
     */
    private String takeScreenshot(Map<String, Object> args) {
        BrowserSession session = sessions.get(args.get("session_id"));
        if (session == null) return ToolRegistry.toolError("Session not found");
        
        boolean fullPage = args.containsKey("full_page") ? (Boolean) args.get("full_page") : true;
        boolean annotate = args.containsKey("annotate") ? (Boolean) args.get("annotate") : false;
        
        try {
            // Generate screenshot filename
            String filename = "browser_screenshot_" + UUID.randomUUID().toString().replace("-", "") + ".png";
            Path screenshotPath = SCREENSHOTS_DIR.resolve(filename);
            
            // Ensure directory exists
            Files.createDirectories(SCREENSHOTS_DIR);
            
            // Take screenshot
            Page.ScreenshotOptions options = new Page.ScreenshotOptions()
                .setPath(screenshotPath)
                .setFullPage(fullPage);
            
            session.page.screenshot(options);
            
            // If annotate is requested, we would need to draw on the image
            // For now, just return the screenshot info
            String result = ToolRegistry.toolResult(Map.of(
                "success", true,
                "screenshot_path", screenshotPath.toString(),
                "full_page", fullPage,
                "annotate", annotate,
                "url", session.page.url()
            ));
            
            logger.info("Screenshot saved to: {}", screenshotPath);
            return result;
            
        } catch (Exception e) {
            logger.error("Screenshot failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Failed to take screenshot: " + e.getMessage());
        }
    }
    
    /**
     * Generate accessibility tree snapshot similar to Playwright's ariaSnapshot.
     */
    private String generateAccessibilitySnapshot(Page page, boolean full) {
        StringBuilder snapshot = new StringBuilder();
        snapshot.append("- webkit title: ").append(page.title()).append("\n");
        snapshot.append("- webkit url: ").append(page.url()).append("\n\n");
        
        // Get interactive elements with their ARIA roles
        List<ElementHandle> interactiveElements = page.querySelectorAll(
            "button, a[href], input, textarea, select, [role='button'], [role='link']"
        );
        
        int refCounter = 1;
        Map<String, String> elementRefs = new HashMap<>();
        
        snapshot.append("Interactive elements:\n");
        for (ElementHandle element : interactiveElements) {
            try {
                String role = getAriaRole(element);
                String name = getAccessibleName(element);
                String text = element.textContent();
                
                if (name == null || name.isEmpty()) {
                    name = text != null ? text.trim() : "";
                }
                
                if (name.length() > 100) {
                    name = name.substring(0, 100) + "...";
                }
                
                if (!name.isEmpty()) {
                    String refId = "@e" + refCounter++;
                    elementRefs.put(refId, getElementSelector(element));
                    snapshot.append("  [").append(refId).append("] ")
                           .append(role).append(": ").append(name).append("\n");
                }
            } catch (Exception e) {
                // Skip elements that can't be processed
            }
        }
        
        if (full) {
            snapshot.append("\nPage content:\n");
            String bodyText = page.textContent("body");
            if (bodyText.length() > 5000) {
                bodyText = bodyText.substring(0, 5000) + "\n... [truncated]";
            }
            snapshot.append(bodyText);
        }
        
        return snapshot.toString();
    }
    
    private String getAriaRole(ElementHandle element) {
        try {
            String role = (String) element.evaluate("el => el.getAttribute('role')");
            if (role != null && !role.isEmpty()) return role;
            
            String tagName = (String) element.evaluate("el => el.tagName.toLowerCase()");
            switch (tagName) {
                case "button": return "button";
                case "a": return "link";
                case "input": 
                    String type = (String) element.evaluate("el => el.type");
                    return "textbox".equals(type) ? "textbox" : "input";
                case "textarea": return "textbox";
                case "select": return "combobox";
                default: return tagName;
            }
        } catch (Exception e) {
            return "element";
        }
    }
    
    private String getAccessibleName(ElementHandle element) {
        try {
            // Try aria-label first
            String ariaLabel = (String) element.evaluate("el => el.getAttribute('aria-label')");
            if (ariaLabel != null && !ariaLabel.isEmpty()) return ariaLabel;
            
            // Try aria-labelledby
            String labelledBy = (String) element.evaluate("el => el.getAttribute('aria-labelledby')");
            if (labelledBy != null && !labelledBy.isEmpty()) {
                // In real implementation, would look up the element by ID
                return labelledBy;
            }
            
            // Try placeholder for inputs
            String placeholder = (String) element.evaluate("el => el.getAttribute('placeholder')");
            if (placeholder != null && !placeholder.isEmpty()) return placeholder;
            
            // Try value for inputs
            String value = (String) element.evaluate("el => el.value");
            if (value != null && !value.isEmpty()) return value;
            
            // Try text content
            String text = element.textContent();
            if (text != null && !text.trim().isEmpty()) return text.trim();
            
            // Try alt for images
            String alt = (String) element.evaluate("el => el.getAttribute('alt')");
            if (alt != null && !alt.isEmpty()) return alt;
            
            return "";
        } catch (Exception e) {
            return "";
        }
    }
    
    private String getElementSelector(ElementHandle element) {
        try {
            // Try to get a unique selector
            String id = (String) element.evaluate("el => el.id");
            if (id != null && !id.isEmpty()) return "#" + id;
            
            // Fallback to tag name with text
            String tagName = (String) element.evaluate("el => el.tagName.toLowerCase()");
            return tagName;
        } catch (Exception e) {
            return "*";
        }
    }
    
    private int countInteractiveElements(Page page) {
        try {
            return page.querySelectorAll("button, a[href], input, textarea, select, [role='button'], [role='link']").size();
        } catch (Exception e) {
            return 0;
        }
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
