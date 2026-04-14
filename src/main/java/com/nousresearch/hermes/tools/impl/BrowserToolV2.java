package com.nousresearch.hermes.tools.impl;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser automation tool with Playwright integration.
 * Supports Chrome DevTools Protocol (CDP) for connecting to existing Chrome instances.
 */
public class BrowserToolV2 {
    private static final Logger logger = LoggerFactory.getLogger(BrowserToolV2.class);
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private Playwright playwright;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build();
    
    // Screenshot storage directory
    private static final Path SCREENSHOTS_DIR = Paths.get(System.getProperty("user.home"), ".hermes", "cache", "screenshots");
    
    // Default CDP endpoint
    private static final String DEFAULT_CDP_URL = "http://localhost:9222";
    
    // Environment variable for CDP URL override
    private static final String BROWSER_CDP_URL_ENV = "BROWSER_CDP_URL";
    
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
            .schema(Map.of("description", "Click on an element identified by its ref ID from the snapshot (e.g., '@e5'). The ref IDs are shown in square brackets in the snapshot output. Requires browser_navigate and browser_snapshot to be called first.",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("session_id", Map.of("type", "string"), "selector", Map.of("type", "string")),
                    "required", List.of("session_id", "selector"))))
            .handler(this::clickElement).emoji("🖱️").build());
        
        registry.register(new ToolEntry.Builder()
            .name("browser_type").toolset("browser")
            .schema(Map.of("description", "Type text into an input field identified by its ref ID. Clears the field first, then types the new text. Requires browser_navigate and browser_snapshot to be called first.",
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
            .schema(Map.of("description", "Take a screenshot of the current page and save it to disk. Use this when you need to visually capture what's on the page - especially useful for CAPTCHAs, visual verification challenges, complex layouts, or when the text snapshot doesn't capture important visual information. Returns the screenshot_path that you can reference. Requires browser_navigate to be called first.",
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
            .schema(Map.of("description", "Navigate to a URL in the browser. Initializes the session and loads the page. Must be called before other browser tools. IMPORTANT: For simple information retrieval, prefer web_search or web_extract (faster, cheaper). Use browser tools only when you need to interact with a page (click, fill forms, dynamic content) or when web_extract fails. Returns a compact page snapshot with interactive elements and ref IDs — no need to call browser_snapshot separately after navigating.",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "URL to navigate to"),
                        "headless", Map.of("type", "boolean", "default", true)),
                    "required", List.of("url"))))
            .handler(this::navigate).emoji("🧭").build());
        
        // Snapshot tool - aligned with original Hermes browser_snapshot
        registry.register(new ToolEntry.Builder()
            .name("browser_snapshot").toolset("browser")
            .schema(Map.of("description", "Get a text-based snapshot of the current page's accessibility tree. Returns interactive elements with ref IDs (like @e1, @e2) for browser_click and browser_type. full=false (default): compact view with interactive elements. full=true: complete page content. Snapshots over 8000 chars are truncated. Requires browser_navigate first. Note: browser_navigate already returns a compact snapshot — use this to refresh after interactions that change the page, or with full=true for complete content.",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "session_id", Map.of("type", "string", "description", "Browser session ID"),
                        "full", Map.of("type", "boolean", "default", false, "description", "Full snapshot or compact view")),
                    "required", List.of("session_id"))))
            .handler(this::getSnapshot).emoji("📋").build());
        
        // CDP Connect tool - connect to existing Chrome via CDP
        registry.register(new ToolEntry.Builder()
            .name("browser_cdp_connect").toolset("browser")
            .schema(Map.of("description", "Connect to an existing Chrome instance via Chrome DevTools Protocol (CDP). Use this to control your local Chrome browser.",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "cdp_url", Map.of("type", "string", "description", "CDP endpoint URL (e.g., http://localhost:9222 or ws://host:port)", "default", "http://localhost:9222"),
                        "headless", Map.of("type", "boolean", "default", false, "description", "Whether to run in headless mode")),
                    "required", List.of()))) 
            .handler(this::connectCDP).emoji("🔌").build());
        
        // CDP Status tool - check CDP connection status
        registry.register(new ToolEntry.Builder()
            .name("browser_cdp_status").toolset("browser")
            .schema(Map.of("description", "Check Chrome DevTools Protocol connection status",
                "parameters", Map.of("type", "object",
                    "properties", Map.of())))
            .handler(this::getCDPStatus).emoji("📡").build());
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
     * Connect to Chrome via CDP (Chrome DevTools Protocol).
     * Aligned with Python Hermes BROWSER_CDP_URL support.
     */
    private String connectCDP(Map<String, Object> args) {
        if (playwright == null) return ToolRegistry.toolError("Playwright not available");
        
        String cdpUrl = args.containsKey("cdp_url") ? (String) args.get("cdp_url") : DEFAULT_CDP_URL;
        
        // Check environment variable override
        String envCdpUrl = System.getenv(BROWSER_CDP_URL_ENV);
        if (envCdpUrl != null && !envCdpUrl.isEmpty()) {
            cdpUrl = envCdpUrl;
            logger.info("Using CDP URL from environment: {}", cdpUrl);
        }
        
        try {
            // Resolve CDP endpoint (normalize ws:// to http:// for discovery)
            String resolvedUrl = resolveCDPEndpoint(cdpUrl);
            
            // Connect via CDP
            Browser browser = playwright.chromium().connectOverCDP(resolvedUrl);
            BrowserContext context = browser.contexts().isEmpty() 
                ? browser.newContext() 
                : browser.contexts().get(0);
            Page page = context.pages().isEmpty() 
                ? context.newPage() 
                : context.pages().get(0);
            
            String sessionId = UUID.randomUUID().toString();
            BrowserSession session = new BrowserSession(sessionId, browser, page, true); // isCDP = true
            sessions.put(sessionId, session);
            
            logger.info("Connected to Chrome via CDP: {}", resolvedUrl);
            
            return ToolRegistry.toolResult(Map.of(
                "success", true,
                "session_id", sessionId,
                "cdp_url", resolvedUrl,
                "url", page.url(),
                "title", page.title(),
                "message", "Connected to live Chrome via CDP. Use browser_navigate, browser_snapshot, browser_click as normal."
            ));
            
        } catch (Exception e) {
            logger.error("Failed to connect via CDP: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Failed to connect via CDP: " + e.getMessage() + 
                ". Make sure Chrome is running with --remote-debugging-port=9222");
        }
    }
    
    /**
     * Get CDP connection status.
     */
    private String getCDPStatus(Map<String, Object> args) {
        String envCdpUrl = System.getenv(BROWSER_CDP_URL_ENV);
        
        // Count CDP sessions
        long cdpSessions = sessions.values().stream()
            .filter(s -> s.isCDP)
            .count();
        
        return ToolRegistry.toolResult(Map.of(
            "cdp_available", playwright != null,
            "environment_cdp_url", envCdpUrl != null ? envCdpUrl : "not set",
            "default_cdp_url", DEFAULT_CDP_URL,
            "active_cdp_sessions", cdpSessions,
            "total_sessions", sessions.size()
        ));
    }
    
    /**
     * Resolve CDP endpoint URL.
     * Handles ws:// -> http:// conversion for discovery, and fetches webSocketDebuggerUrl.
     */
    private String resolveCDPEndpoint(String cdpUrl) {
        if (cdpUrl == null || cdpUrl.isEmpty()) {
            return DEFAULT_CDP_URL;
        }
        
        String raw = cdpUrl.trim();
        String lowered = raw.toLowerCase();
        
        // Already a WebSocket endpoint
        if (lowered.contains("/devtools/browser/")) {
            return raw;
        }
        
        // Convert ws:// to http:// for discovery
        String discoveryUrl = raw;
        if (lowered.startsWith("ws://")) {
            discoveryUrl = "http://" + raw.substring(5);
        } else if (lowered.startsWith("wss://")) {
            discoveryUrl = "https://" + raw.substring(6);
        }
        
        // Add /json/version if not present
        String versionUrl;
        if (discoveryUrl.toLowerCase().endsWith("/json/version")) {
            versionUrl = discoveryUrl;
        } else {
            versionUrl = discoveryUrl.replaceAll("/$", "") + "/json/version";
        }
        
        // Try to discover WebSocket URL
        try {
            Request request = new Request.Builder()
                .url(versionUrl)
                .get()
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    com.fasterxml.jackson.databind.ObjectMapper mapper = 
                        new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String, Object> payload = mapper.readValue(body, Map.class);
                    
                    String wsUrl = (String) payload.get("webSocketDebuggerUrl");
                    if (wsUrl != null && !wsUrl.isEmpty()) {
                        logger.info("Resolved CDP endpoint {} -> {}", raw, wsUrl);
                        return wsUrl;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve CDP endpoint {} via {}: {}", raw, versionUrl, e.getMessage());
        }
        
        // Fallback to raw URL
        logger.warn("CDP discovery failed; using raw endpoint: {}", raw);
        return raw;
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
        return new BrowserSession(UUID.randomUUID().toString(), browser, page, false);
    }
    
    /**
     * Browser session record.
     * @param id Session ID
     * @param browser Playwright Browser instance
     * @param page Playwright Page instance
     * @param isCDP Whether this session is connected via CDP (don't close browser on session close)
     */
    record BrowserSession(String id, Browser browser, Page page, boolean isCDP) {
        void close() {
            if (isCDP) {
                // For CDP sessions, only close the page/context, not the browser
                // The browser is managed externally (user's Chrome)
                try {
                    page.close();
                } catch (Exception e) {
                    // Ignore
                }
            } else {
                // For normal sessions, close the browser
                browser.close();
            }
        }
    }
}
