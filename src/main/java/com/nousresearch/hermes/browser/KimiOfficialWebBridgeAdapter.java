package com.nousresearch.hermes.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Discovery/status adapter for the official Kimi WebBridge daemon.
 *
 * <p>The official Kimi WebBridge integration is skill-backed: the Kimi installer
 * drops a version-matched AgentSkill that owns the full browser operation protocol.
 * Hermes core intentionally does not duplicate that skill logic. This adapter keeps
 * core BrowserBridge aware of daemon health and capabilities while directing real
 * operations through the installed {@code kimi-webbridge} skill.</p>
 */
public class KimiOfficialWebBridgeAdapter implements BrowserBridge {
    private static final List<String> OFFICIAL_ACTIONS = List.of(
        "navigate", "find_tab", "snapshot", "click", "fill", "evaluate", "screenshot",
        "network", "upload", "save_as_pdf", "list_tabs", "close_tab", "close_session"
    );

    private final BrowserBridgeConfig config;
    private final String endpoint;
    private final int timeoutMs;
    private final HttpClient client;
    private volatile Map<String, Object> lastStatus = Map.of();

    public KimiOfficialWebBridgeAdapter(BrowserBridgeConfig config) {
        this.config = config != null ? config : new BrowserBridgeConfig("kimi-webbridge", "http://127.0.0.1:10086", 10000);
        this.endpoint = blankToDefault(this.config.endpoint(), "http://127.0.0.1:10086");
        this.timeoutMs = Math.max(1000, this.config.timeoutMs());
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
    }

    @Override
    public BrowserActionResult execute(BrowserAction action) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", "kimi-webbridge");
        meta.put("mode", "skill-backed");
        meta.put("skill", "kimi-webbridge");
        meta.put("endpoint", endpoint);
        if (!lastStatus.isEmpty()) meta.put("status", lastStatus);
        return BrowserActionResult.error(
            action != null ? action.sessionId() : null,
            "skill_backed_provider",
            "Kimi WebBridge operations are provided by the installed kimi-webbridge skill; Hermes core only performs discovery/status for this provider.",
            meta
        );
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", "kimi-webbridge");
        map.put("class", getClass().getName());
        map.put("mode", "skill-backed");
        map.put("skill", "kimi-webbridge");
        map.put("endpoint", endpoint);
        map.put("status_path", "/status");
        map.put("command_path", "/command");
        map.put("healthy", Boolean.TRUE.equals(lastStatus.get("running")));
        addSkillDiscovery(map);
        if (!lastStatus.isEmpty()) map.put("last_status", lastStatus);
        return map;
    }

    @Override
    public BrowserActionResult healthCheck() {
        try {
            Map<String, Object> status = fetchStatus();
            lastStatus = status;
            boolean running = Boolean.TRUE.equals(status.get("running"));
            boolean extensionConnected = Boolean.TRUE.equals(status.get("extension_connected"));
            String message = running
                ? (extensionConnected ? "Kimi WebBridge daemon and extension are connected" : "Kimi WebBridge daemon is running but browser extension is not connected")
                : "Kimi WebBridge daemon is not running";
            Map<String, Object> meta = new LinkedHashMap<>(status);
            meta.put("provider", "kimi-webbridge");
            meta.put("mode", "skill-backed");
            meta.put("skill", "kimi-webbridge");
            meta.put("endpoint", endpoint);
            meta.put("extension_required", true);
            addSkillDiscovery(meta);
            return running
                ? BrowserActionResult.ok(null, endpoint, "Kimi WebBridge", null, message, List.of(), meta)
                : BrowserActionResult.error(null, "daemon_not_running", message, meta);
        } catch (Exception e) {
            return exceptionError("status", e);
        }
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("provider", "kimi-webbridge");
        map.put("mode", "skill-backed");
        map.put("skill", "kimi-webbridge");
        map.put("endpoint", endpoint);
        map.put("status_path", "/status");
        map.put("command_path", "/command");
        map.put("actions", OFFICIAL_ACTIONS);
        map.put("features", List.of("real-browser", "local-daemon", "browser-extension", "cdp", "tabs", "screenshots", "pdf", "network", "skill-backed"));
        addSkillDiscovery(map);
        try {
            Map<String, Object> status = fetchStatus();
            lastStatus = status;
            map.put("daemon", status);
            map.put("daemon_running", Boolean.TRUE.equals(status.get("running")));
            map.put("extension_connected", Boolean.TRUE.equals(status.get("extension_connected")));
            map.put("usable", Boolean.TRUE.equals(status.get("running")) && Boolean.TRUE.equals(status.get("extension_connected")));
        } catch (Exception e) {
            map.put("ok", false);
            map.put("error_code", classifyException(e));
            map.put("message", "Kimi WebBridge status unavailable: " + e.getMessage());
        }
        return map;
    }

    private Map<String, Object> fetchStatus() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(resolve("/status"))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("status HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonNode json = HttpBrowserBridge.MAPPER.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
        return HttpBrowserBridge.MAPPER.convertValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private void addSkillDiscovery(Map<String, Object> map) {
        Path skillPath = findKimiWebBridgeSkill();
        boolean installed = skillPath != null;
        map.put("skill_installed", installed);
        if (installed) {
            map.put("skill_path", skillPath.toString());
            map.put("recommended_invocation", "skill_get(\"kimi-webbridge\") then follow the skill instructions");
        } else {
            map.put("recommended_install", "~/.kimi-webbridge/bin/kimi-webbridge install-skill -y");
        }
    }

    private static Path findKimiWebBridgeSkill() {
        String home = System.getProperty("user.home", "");
        java.util.LinkedHashSet<Path> candidates = new java.util.LinkedHashSet<>();
        addSkillPathCandidates(candidates, System.getProperty("hermes.skills.paths"));
        addSkillPathCandidates(candidates, System.getenv("HERMES_SKILLS_PATHS"));
        addSkillPathCandidates(candidates, System.getenv("HERMES_EXTRA_SKILLS_DIRS"));
        candidates.add(Paths.get(home, ".openclaw", "skills", "kimi-webbridge"));
        candidates.add(Paths.get(home, ".openclaw", "workspace", "skills", "kimi-webbridge"));
        candidates.add(Paths.get(home, ".hermes", "skills", "kimi-webbridge"));
        for (Path dir : candidates) {
            Path skill = dir.resolve("SKILL.md");
            if (Files.exists(skill)) return dir.toAbsolutePath().normalize();
        }
        return null;
    }

    private static void addSkillPathCandidates(java.util.Set<Path> candidates, String value) {
        if (value == null || value.isBlank()) return;
        for (String part : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (part == null || part.isBlank()) continue;
            Path base = Paths.get(part.trim());
            Path fileName = base.getFileName();
            candidates.add("kimi-webbridge".equals(fileName != null ? fileName.toString() : "") ? base : base.resolve("kimi-webbridge"));
        }
    }

    private URI resolve(String path) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    private BrowserActionResult exceptionError(String operation, Exception e) {
        String code = classifyException(e);
        return BrowserActionResult.error(null, code, "Kimi WebBridge " + operation + " unavailable: " + e.getMessage(), Map.of(
            "provider", "kimi-webbridge",
            "mode", "skill-backed",
            "operation", operation,
            "endpoint", endpoint,
            "exception", e.getClass().getSimpleName()
        ));
    }

    private static String classifyException(Exception e) {
        String name = e.getClass().getName().toLowerCase();
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (e instanceof ConnectException || message.contains("connect") || message.contains("refused")) return "daemon_unavailable";
        if (e instanceof TimeoutException || name.contains("timeout") || message.contains("timeout")) return "navigation_timeout";
        return "bridge_unavailable";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 500 ? value.substring(0, 500) + "..." : value;
    }
}
