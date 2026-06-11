package com.nousresearch.hermes.browser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory bridge for tests and installations without a real browser daemon.
 */
public class MockBrowserBridge implements BrowserBridge {
    private final ConcurrentHashMap<String, MockSession> sessions = new ConcurrentHashMap<>();
    private volatile String lastSessionId;

    @Override
    public BrowserActionResult execute(BrowserAction action) {
        return switch (action.action()) {
            case "open" -> open(action);
            case "observe" -> observe(action);
            case "click", "type", "extract", "scroll", "press", "submit" -> mutate(action);
            case "close" -> close(action);
            default -> BrowserActionResult.error(action.sessionId(), "Unsupported mock browser action: " + action.action());
        };
    }

    private BrowserActionResult open(BrowserAction action) {
        String id = "mock-" + UUID.randomUUID().toString().substring(0, 8);
        MockSession session = new MockSession(id, action.url(), "Mock Browser - " + action.url());
        session.record(action, "opened");
        sessions.put(id, session);
        lastSessionId = id;
        return session.result("opened");
    }

    private BrowserActionResult observe(BrowserAction action) {
        MockSession session = requireSession(action.sessionId());
        if (session == null) return BrowserActionResult.error(action.sessionId(), "Browser session not found");
        session.record(action, "observed");
        return session.result("observed");
    }

    private BrowserActionResult mutate(BrowserAction action) {
        MockSession session = requireSession(action.sessionId());
        if (session == null) return BrowserActionResult.error(action.sessionId(), "Browser session not found");
        session.record(action, "performed " + action.action());
        return session.result("performed " + action.action());
    }

    private BrowserActionResult close(BrowserAction action) {
        MockSession session = requireSession(action.sessionId());
        if (session == null) return BrowserActionResult.error(action.sessionId(), "Browser session not found");
        session.record(action, "closed");
        sessions.remove(session.id);
        if (session.id.equals(lastSessionId)) lastSessionId = null;
        return BrowserActionResult.ok(session.id, session.url, session.title, "", "closed", List.copyOf(session.actions));
    }

    private MockSession requireSession(String id) {
        String resolved = id != null ? id : lastSessionId;
        return resolved != null ? sessions.get(resolved) : null;
    }

    private static final class MockSession {
        final String id;
        final String url;
        final String title;
        final List<Map<String, Object>> actions = new ArrayList<>();

        MockSession(String id, String url, String title) {
            this.id = id;
            this.url = url;
            this.title = title;
        }

        void record(BrowserAction action, String status) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("at", Instant.now().toString());
            entry.put("action", action.action());
            entry.put("status", status);
            if (action.target() != null) entry.put("target", action.target());
            if (action.text() != null) entry.put("text", action.text());
            if (action.instruction() != null) entry.put("instruction", action.instruction());
            if (action.actor() != null) entry.put("actor", action.actor());
            actions.add(entry);
        }

        BrowserActionResult result(String message) {
            String content = "Mock page at " + url + " with " + actions.size() + " browser action(s).";
            return BrowserActionResult.ok(id, url, title, content, message, List.copyOf(actions));
        }
    }
}
