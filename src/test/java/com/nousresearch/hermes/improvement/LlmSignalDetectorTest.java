package com.nousresearch.hermes.improvement;

import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link LlmSignalDetector}.
 *
 * <p>Covers: trigger-word detection (no LLM), LLM classification mock,
 * repeat pattern similarity, edge cases.</p>
 */
class LlmSignalDetectorTest {

    private LocalSignalStore signalStore;
    private SignalCollector collector;
    private LlmSignalDetector detector;

    private final String tenantId = "test-tenant";
    private final String userId = "usr_test";
    private final String sessionId = "ses_001";

    @BeforeEach
    void setUp() {
        signalStore = new LocalSignalStore();
        collector = new SignalCollector(signalStore);
        // No LLM mode (trigger-word-only)
        detector = new LlmSignalDetector(collector);
    }

    // ── USER_CORRECTION (trigger-word only) ──

    @Test
    void correctionDetectedChinese() {
        assertTrue(detector.detectCorrection(tenantId, userId, sessionId,
                "不对，应该是返回 List 而不是 Array"));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION));
    }

    @Test
    void correctionDetectedEnglish() {
        assertTrue(detector.detectCorrection(tenantId, userId, sessionId,
                "No, that's wrong. The function should return a List."));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION));
    }

    @Test
    void correctionNotDetectedForNormalMessage() {
        assertFalse(detector.detectCorrection(tenantId, userId, sessionId,
                "Can you help me write a function?"));
        assertEquals(0, signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION));
    }

    @Test
    void correctionNotDetectedForNewQuestion() {
        assertFalse(detector.detectCorrection(tenantId, userId, sessionId,
                "What about using Postgres instead of MySQL?"));
        assertEquals(0, signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION));
    }

    @Test
    void correctionNullMessageReturnsFalse() {
        assertFalse(detector.detectCorrection(tenantId, userId, sessionId, null));
        assertFalse(detector.detectCorrection(tenantId, userId, sessionId, ""));
        assertFalse(detector.detectCorrection(tenantId, userId, sessionId, "  "));
    }

    // ── EXPLICIT_FEEDBACK (trigger-word only) ──

    @Test
    void feedbackDetectedChinese() {
        String result = detector.detectExplicitFeedback(tenantId, userId, sessionId,
                "记住我偏好简洁的回复");
        assertNotNull(result);
        assertTrue(result.contains("记住我偏好简洁的回复"));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.EXPLICIT_FEEDBACK));
    }

}
