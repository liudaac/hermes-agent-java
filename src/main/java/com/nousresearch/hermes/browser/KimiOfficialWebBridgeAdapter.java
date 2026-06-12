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
        if (action == null) {
            return BrowserActionResult.error(null, "invalid_action", "Browser action is required", baseMeta(null));
        }
        try {
            MappedCommand mapped = mapAction(action);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", mapped.action());
            body.put("args", mapped.args());
            body.put("session", session(action));
            HttpRequest request = HttpRequest.newBuilder(resolve("/command"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(HttpBrowserBridge.MAPPER.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return toBrowserResult(action, mapped, response.statusCode(), response.body());
        } catch (IllegalArgumentException e) {
            return BrowserActionResult.error(action.sessionId(), "invalid_action", e.getMessage(), baseMeta(action));
        } catch (Exception e) {
            return exceptionError("execute", e, action.sessionId());
        }
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
            return exceptionError("status", e, null);
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


    private BrowserActionResult toBrowserResult(BrowserAction original, MappedCommand mapped, int statusCode, String body) throws Exception {
        Map<String, Object> response = parseResponse(body);
        Map<String, Object> meta = baseMeta(original);
        meta.put("mapped_action", mapped.action());
        meta.put("http_status", statusCode);
        meta.put("response", response);
        if (statusCode < 200 || statusCode >= 300) {
            return BrowserActionResult.error(session(original), "http_error", "Kimi WebBridge HTTP " + statusCode + ": " + truncate(body), meta);
        }
        Object okValue = response.get("ok");
        if (Boolean.FALSE.equals(okValue)) {
            Map<String, Object> error = response.get("error") instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new))
                : Map.of();
            String code = String.valueOf(error.getOrDefault("code", "tool_error"));
            String message = String.valueOf(error.getOrDefault("message", "Kimi WebBridge command failed"));
            return BrowserActionResult.error(session(original), code, message, meta);
        }
        String url = stringValue(response.getOrDefault("url", original.url()));
        String title = stringValue(response.get("title"));
        String content = firstText(response, "tree", "value", "text", "content", "path");
        String message = "Kimi WebBridge command executed: " + mapped.action();
        return BrowserActionResult.ok(session(original), url, title, content, message, List.of(), meta);
    }

    private Map<String, Object> parseResponse(String body) throws Exception {
        if (body == null || body.isBlank()) return Map.of();
        JsonNode json = HttpBrowserBridge.MAPPER.readTree(body);
        return HttpBrowserBridge.MAPPER.convertValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> baseMeta(BrowserAction action) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", "kimi-webbridge");
        meta.put("mode", "skill-backed");
        meta.put("skill", "kimi-webbridge");
        meta.put("endpoint", endpoint);
        addSkillDiscovery(meta);
        if (!lastStatus.isEmpty()) meta.put("status", lastStatus);
        if (action != null) {
            meta.put("requested_action", action.action());
            meta.put("actor", action.actor());
            meta.put("reason", action.reason());
        }
        return meta;
    }

    private MappedCommand mapAction(BrowserAction action) {
        String requested = action.action() == null || action.action().isBlank() ? "snapshot" : action.action().trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> args = new LinkedHashMap<>();
        return switch (requested) {
            case "open", "navigate" -> {
                if (action.url() == null || action.url().isBlank()) throw new IllegalArgumentException("url is required for navigate/open");
                args.put("url", action.url());
                args.put("newTab", true);
                if (action.instruction() != null && !action.instruction().isBlank()) args.put("group_title", action.instruction());
                yield new MappedCommand("navigate", args);
            }
            case "observe", "snapshot", "extract", "read" -> new MappedCommand("snapshot", args);
            case "click" -> {
                if (action.target() == null || action.target().isBlank()) throw new IllegalArgumentException("target selector/ref is required for click");
                args.put("selector", action.target());
                yield new MappedCommand("click", args);
            }
            case "type", "fill" -> {
                if (action.target() == null || action.target().isBlank()) throw new IllegalArgumentException("target selector/ref is required for fill");
                args.put("selector", action.target());
                args.put("value", action.text() != null ? action.text() : "");
                yield new MappedCommand("fill", args);
            }
            case "evaluate" -> {
                String code = action.instruction() != null && !action.instruction().isBlank() ? action.instruction() : action.text();
                if (code == null || code.isBlank()) throw new IllegalArgumentException("instruction/text JavaScript code is required for evaluate");
                args.put("code", code);
                yield new MappedCommand("evaluate", args);
            }
            case "screenshot" -> new MappedCommand("screenshot", args);
            case "list_tabs" -> new MappedCommand("list_tabs", args);
            case "find_tab" -> {
                if (action.url() != null && !action.url().isBlank()) args.put("url", action.url());
                if (action.target() != null && !action.target().isBlank()) args.put("url", action.target());
                if (!args.containsKey("url")) args.put("active", true);
                yield new MappedCommand("find_tab", args);
            }
            case "close_tab" -> new MappedCommand("close_tab", args);
            case "close", "close_session" -> new MappedCommand("close_session", args);
            case "save_as_pdf" -> new MappedCommand("save_as_pdf", args);
            default -> throw new IllegalArgumentException("Unsupported Kimi WebBridge browser action: " + requested);
        };
    }

    private static String session(BrowserAction action) {
        return action != null && action.sessionId() != null && !action.sessionId().isBlank()
            ? action.sessionId()
            : "hermes-browser";
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) return String.valueOf(value);
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record MappedCommand(String action, Map<String, Object> args) {}

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

    private BrowserActionResult exceptionError(String operation, Exception e, String sessionId) {
        String code = classifyException(e);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", "kimi-webbridge");
        meta.put("mode", "skill-backed");
        meta.put("operation", operation);
        meta.put("endpoint", endpoint);
        meta.put("exception", e.getClass().getSimpleName());
        return BrowserActionResult.error(sessionId, code, "Kimi WebBridge " + operation + " unavailable: " + e.getMessage(), meta);
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
