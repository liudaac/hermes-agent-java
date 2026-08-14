package com.nousresearch.hermes.harness.session.library;

import com.nousresearch.hermes.gateway.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionStepExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExtractStepsFromToolCalls() {
        var sessionMgr = new SessionManager(tempDir);
        var session = sessionMgr.getSession("test-session");
        session.addMessage("user", "Deploy the app");
        session.addMessage("assistant", "I'll deploy the app for you");
        session.recordToolCall("terminal", true, 1500);
        session.recordToolCall("write_file", true, 300);
        session.recordToolCall("send_message", true, 500);

        var extractor = new SessionStepExtractor();
        var steps = extractor.extract(session);

        assertEquals(3, steps.size());
        assertEquals("terminal", steps.get(0).action());
        assertTrue(steps.get(0).keyStep()); // terminal is a key tool
        assertEquals("write_file", steps.get(1).action());
        assertTrue(steps.get(1).keyStep()); // write is a key tool
    }

    @Test
    void shouldFallbackToMessagesWhenNoToolCalls() {
        var sessionMgr = new SessionManager(tempDir);
        var session = sessionMgr.getSession("test-session-2");
        session.addMessage("user", "Hello");
        session.addMessage("assistant", "I'll help you with that task. First, let me check the configuration.");
        session.addMessage("user", "Thanks");

        var extractor = new SessionStepExtractor();
        var steps = extractor.extract(session);

        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().anyMatch(s -> s.action().contains("help you")));
    }

    @Test
    void shouldGenerateTitleFromFirstUserMessage() {
        var sessionMgr = new SessionManager(tempDir);
        var session = sessionMgr.getSession("test-session-3");
        session.addMessage("user", "Please help me deploy the application to production environment");

        var extractor = new SessionStepExtractor();
        var title = extractor.generateTitle(session);

        assertNotNull(title);
        assertTrue(title.contains("deploy"));
        assertTrue(title.length() <= 63); // 60 + "..."
    }

    @Test
    void shouldGenerateSummary() {
        var sessionMgr = new SessionManager(tempDir);
        var session = sessionMgr.getSession("test-session-4");
        session.addMessage("user", "Do task 1");
        session.addMessage("assistant", "Done");
        session.addMessage("user", "Do task 2");
        session.addMessage("assistant", "Done");
        session.recordToolCall("terminal", true, 100);
        session.recordToolCall("write_file", true, 200);

        var extractor = new SessionStepExtractor();
        var summary = extractor.generateSummary(session);

        assertNotNull(summary);
        assertTrue(summary.contains("2 条用户消息"));
        assertTrue(summary.contains("2 次工具调用"));
    }

    @Test
    void shouldBuildReferenceContext() {
        var steps = List.of(
                new SessionAsset.StepSummary(0, "Confirm environment", "ask_user", "staging", false, 1000),
                new SessionAsset.StepSummary(1, "Pull latest code", "terminal", "success", true, 2000),
                new SessionAsset.StepSummary(2, "Run tests", "terminal", "42 passed", false, 3000)
        );

        var extractor = new SessionStepExtractor();
        var reference = extractor.buildReferenceContext("Deploy Flow", steps);

        assertNotNull(reference);
        assertTrue(reference.contains("Deploy Flow"));
        assertTrue(reference.contains("Confirm environment"));
        assertTrue(reference.contains("ask_user"));
        assertTrue(reference.contains("⭐")); // key step marker
        assertTrue(reference.contains("参考上述流程"));
    }
}
