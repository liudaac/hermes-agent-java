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

    @Test
    void feedbackDetectedEnglish() {
        String result = detector.detectExplicitFeedback(tenantId, userId, sessionId,
                "Remember I prefer concise answers");
        assertNotNull(result);
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.EXPLICIT_FEEDBACK));
    }

    @Test
    void feedbackNotDetectedForNormalMessage() {
        assertNull(detector.detectExplicitFeedback(tenantId, userId, sessionId,
                "Help me debug this error"));
        assertEquals(0, signalStore.countByType(tenantId, userId, SignalType.EXPLICIT_FEEDBACK));
    }

    @Test
    void feedbackNullMessageReturnsNull() {
        assertNull(detector.detectExplicitFeedback(tenantId, userId, sessionId, null));
        assertNull(detector.detectExplicitFeedback(tenantId, userId, sessionId, ""));
    }

    // ── REPEAT_PATTERN ──

    @Test
    void repeatPatternDetectedHighSimilarity() {
        String current = "deploy the staging environment with docker compose";
        String recent = "deploy the staging environment with docker compose and nginx";

        assertTrue(detector.detectRepeatPattern(tenantId, userId, sessionId, current,
                List.of(recent)));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.REPEAT_PATTERN));
    }

    @Test
    void repeatPatternNotDetectedLowSimilarity() {
        String current = "write a python script to parse json";
        String recent = "deploy the staging environment with docker";

        assertFalse(detector.detectRepeatPattern(tenantId, userId, sessionId, current,
                List.of(recent)));
        assertEquals(0, signalStore.countByType(tenantId, userId, SignalType.REPEAT_PATTERN));
    }

    @Test
    void repeatPatternNotDetectedEmptyHistory() {
        assertFalse(detector.detectRepeatPattern(tenantId, userId, sessionId,
                "deploy staging", List.of()));
        assertFalse(detector.detectRepeatPattern(tenantId, userId, sessionId,
                "deploy staging", null));
    }

    @Test
    void repeatPatternNotDetectedNullMessage() {
        assertFalse(detector.detectRepeatPattern(tenantId, userId, sessionId,
                null, List.of("deploy staging")));
        assertFalse(detector.detectRepeatPattern(tenantId, userId, sessionId,
                "", List.of("deploy staging")));
    }

    @Test
    void repeatPatternChecksAllHistory() {
        String current = "run the unit tests for the billing module";
        List<String> history = List.of(
                "write a new feature for auth",
                "deploy the app to production",
                "run the unit tests for the billing module today"  // This one matches
        );

        assertTrue(detector.detectRepeatPattern(tenantId, userId, sessionId, current, history));
    }

    @Test
    void repeatPatternOnlyEmitsOnce() {
        String current = "deploy staging with docker";
        List<String> history = List.of(
                "deploy staging with docker compose",
                "deploy staging with docker and nginx",
                "deploy staging with docker and redis"
        );

        assertTrue(detector.detectRepeatPattern(tenantId, userId, sessionId, current, history));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.REPEAT_PATTERN));
    }

    // ── LLM-backed detection (mocked) ──

    @Test
    void llmClassifiesAsCorrection() {
        ModelClient modelClient = mock(ModelClient.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                ModelMessage.assistant("CORRECTION"), "stop", false);
        when(modelClient.chatCompletion(any(), any(), anyBoolean(), any())).thenReturn(response);

        LlmSignalDetector llmDetector = new LlmSignalDetector(modelClient, collector);

        assertTrue(llmDetector.detectCorrection(tenantId, userId, sessionId,
                "不对，这个 API 名字写错了"));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION));
    }

    @Test
    void llmClassifiesAsNotCorrection() {
        ModelClient modelClient = mock(ModelClient.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                ModelMessage.assistant("NOT_CORRECTION"), "stop", false);
        when(modelClient.chatCompletion(any(), any(), anyBoolean(), any())).thenReturn(response);

        LlmSignalDetector llmDetector = new LlmSignalDetector(modelClient, collector);

        // Trigger word matches but LLM says not a correction
        assertFalse(llmDetector.detectCorrection(tenantId, userId, sessionId,
                "不对，等等，让我想想..."));
        assertEquals(0, signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION));
    }

    @Test
    void llmFailureFallsBackToTriggerMatch() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chatCompletion(any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("API timeout"));

        LlmSignalDetector llmDetector = new LlmSignalDetector(modelClient, collector);

        // LLM fails, but trigger matched -> still emit signal
        assertTrue(llmDetector.detectCorrection(tenantId, userId, sessionId,
                "不对，这应该是 List"));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION));
    }

    @Test
    void llmExtractsPreference() {
        ModelClient modelClient = mock(ModelClient.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                ModelMessage.assistant("response_style: concise"), "stop", false);
        when(modelClient.chatCompletion(any(), any(), anyBoolean(), any())).thenReturn(response);

        LlmSignalDetector llmDetector = new LlmSignalDetector(modelClient, collector);

        String result = llmDetector.detectExplicitFeedback(tenantId, userId, sessionId,
                "Remember I prefer concise answers");
        assertEquals("response_style: concise", result);
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.EXPLICIT_FEEDBACK));
    }

    @Test
    void llmExtractsNonePreference() {
        ModelClient modelClient = mock(ModelClient.class);
        ChatCompletionResponse response = new ChatCompletionResponse(
                ModelMessage.assistant("NONE"), "stop", false);
        when(modelClient.chatCompletion(any(), any(), anyBoolean(), any())).thenReturn(response);

        LlmSignalDetector llmDetector = new LlmSignalDetector(modelClient, collector);

        // Trigger matched but LLM says no preference
        assertNull(llmDetector.detectExplicitFeedback(tenantId, userId, sessionId,
                "记住这个项目很重要"));
        assertEquals(0, signalStore.countByType(tenantId, userId, SignalType.EXPLICIT_FEEDBACK));
    }

    @Test
    void llmFeedbackFailureFallsBackToRawMessage() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chatCompletion(any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("API error"));

        LlmSignalDetector llmDetector = new LlmSignalDetector(modelClient, collector);

        String result = llmDetector.detectExplicitFeedback(tenantId, userId, sessionId,
                "记住我偏好简洁回复");
        assertNotNull(result);
        assertTrue(result.startsWith("other:"));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.EXPLICIT_FEEDBACK));
    }

    // ── Signal content verification ──

    @Test
    void correctionSignalContentContainsMessage() {
        detector.detectCorrection(tenantId, userId, sessionId,
                "不对，API 名称应该是 chatCompletion 不是 complete");

        List<ImprovementSignal> signals = signalStore.queryByType(tenantId, userId, SignalType.USER_CORRECTION);
        assertEquals(1, signals.size());
        assertTrue(signals.get(0).content().contains("chatCompletion"));
        assertEquals(1.0, signals.get(0).weight());
    }

    @Test
    void feedbackSignalContentContainsPreference() {
        detector.detectExplicitFeedback(tenantId, userId, sessionId,
                "以后都先跑测试再部署");

        List<ImprovementSignal> signals = signalStore.queryByType(tenantId, userId, SignalType.EXPLICIT_FEEDBACK);
        assertEquals(1, signals.size());
        assertTrue(signals.get(0).content().contains("以后都先跑测试"));
    }

    @Test
    void repeatPatternSignalContainsSimilarityScore() {
        String msg = "deploy staging with docker compose";
        detector.detectRepeatPattern(tenantId, userId, sessionId, msg,
                List.of("deploy staging with docker compose and nginx"));

        List<ImprovementSignal> signals = signalStore.queryByType(tenantId, userId, SignalType.REPEAT_PATTERN);
        assertEquals(1, signals.size());
        assertTrue(signals.get(0).content().contains("similarity="));
        assertEquals(0.5, signals.get(0).weight());
    }
}
