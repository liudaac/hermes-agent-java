package com.nousresearch.hermes.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class LoopErrorClassifierTest {

    // ==================== Exception-based classification ====================

    @Test
    void nullExceptionIsFatal() {
        assertEquals(ErrorCategory.FATAL, LoopErrorClassifier.classify((Exception) null));
    }

    @Test
    void socketTimeoutIsTransient() {
        assertEquals(ErrorCategory.TRANSIENT,
            LoopErrorClassifier.classify(new SocketTimeoutException("read timed out")));
    }

    @Test
    void connectExceptionIsTransient() {
        assertEquals(ErrorCategory.TRANSIENT,
            LoopErrorClassifier.classify(new ConnectException("Connection refused")));
    }

    @Test
    void timeoutExceptionIsTransient() {
        assertEquals(ErrorCategory.TRANSIENT,
            LoopErrorClassifier.classify(new TimeoutException("operation timed out")));
    }

    @Test
    void ioExceptionIsTransient() {
        assertEquals(ErrorCategory.TRANSIENT,
            LoopErrorClassifier.classify(new IOException("stream closed")));
    }

    @Test
    void noSuchFileIsUserFixable() {
        assertEquals(ErrorCategory.USER_FIXABLE,
            LoopErrorClassifier.classify(new java.nio.file.NoSuchFileException("/tmp/missing.txt")));
    }

    @Test
    void securityExceptionIsUserFixable() {
        assertEquals(ErrorCategory.USER_FIXABLE,
            LoopErrorClassifier.classify(new SecurityException("access denied")));
    }

    @Test
    void illegalArgumentIsLlmRecoverable() {
        assertEquals(ErrorCategory.LLM_RECOVERABLE,
            LoopErrorClassifier.classify(new IllegalArgumentException("missing required parameter")));
    }

    @Test
    void numberFormatIsLlmRecoverable() {
        assertEquals(ErrorCategory.LLM_RECOVERABLE,
            LoopErrorClassifier.classify(new NumberFormatException("For input string: \"abc\"")));
    }

    @Test
    void genericRuntimeExceptionIsFatal() {
        assertEquals(ErrorCategory.FATAL,
            LoopErrorClassifier.classify(new RuntimeException("something went wrong")));
    }

    // ==================== Message-based classification ====================

    @ParameterizedTest
    @ValueSource(strings = {
        "rate limit exceeded",
        "429 Too Many Requests",
        "Request timed out after 30000ms",
        "service unavailable",
        "connection refused",
        "502 Bad Gateway",
        "503 Service Unavailable",
        "server overloaded"
    })
    void transientMessages(String msg) {
        Exception e = new RuntimeException(msg);
        assertEquals(ErrorCategory.TRANSIENT, LoopErrorClassifier.classify(e));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "permission denied",
        "File not found: /data/config.yml",
        "quota exceeded: max 100 requests",
        "403 Forbidden",
        "404 Not Found",
        "access denied to resource"
    })
    void userFixableMessages(String msg) {
        Exception e = new RuntimeException(msg);
        assertEquals(ErrorCategory.USER_FIXABLE, LoopErrorClassifier.classify(e));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "invalid tool call: missing 'name' field",
        "malformed JSON in response",
        "parse error: unexpected token at position 42",
        "missing required argument 'file_path'",
        "400 Bad Request: invalid format"
    })
    void llmRecoverableMessages(String msg) {
        Exception e = new RuntimeException(msg);
        assertEquals(ErrorCategory.LLM_RECOVERABLE, LoopErrorClassifier.classify(e));
    }

    // ==================== String-based classification ====================

    @Test
    void classifyErrorString() {
        assertEquals(ErrorCategory.TRANSIENT,
            LoopErrorClassifier.classify("API error: 429 rate limit exceeded"));
        assertEquals(ErrorCategory.USER_FIXABLE,
            LoopErrorClassifier.classify("permission denied: cannot write to /root"));
        assertEquals(ErrorCategory.LLM_RECOVERABLE,
            LoopErrorClassifier.classify("parse error: unexpected token"));
        assertEquals(ErrorCategory.FATAL,
            LoopErrorClassifier.classify("unknown internal error"));
    }

    @Test
    void nullOrBlankStringIsFatal() {
        assertEquals(ErrorCategory.FATAL, LoopErrorClassifier.classify((String) null));
        assertEquals(ErrorCategory.FATAL, LoopErrorClassifier.classify(""));
        assertEquals(ErrorCategory.FATAL, LoopErrorClassifier.classify("   "));
    }

    // ==================== Cause chain ====================

    @Test
    void causeChainTransient() {
        Exception wrapper = new RuntimeException("operation failed", new SocketTimeoutException("timed out"));
        assertEquals(ErrorCategory.TRANSIENT, LoopErrorClassifier.classify(wrapper));
    }

    @Test
    void causeChainUserFixable() {
        Exception wrapper = new RuntimeException("can't proceed", new SecurityException("access denied"));
        assertEquals(ErrorCategory.USER_FIXABLE, LoopErrorClassifier.classify(wrapper));
    }

    // ==================== ErrorCategory methods ====================

    @Test
    void categoryFlags() {
        assertTrue(ErrorCategory.TRANSIENT.isRetryable());
        assertFalse(ErrorCategory.LLM_RECOVERABLE.isRetryable());
        assertFalse(ErrorCategory.USER_FIXABLE.isRetryable());
        assertFalse(ErrorCategory.FATAL.isRetryable());

        assertTrue(ErrorCategory.TRANSIENT.shouldContinueLoop());
        assertTrue(ErrorCategory.LLM_RECOVERABLE.shouldContinueLoop());
        assertFalse(ErrorCategory.USER_FIXABLE.shouldContinueLoop());
        assertFalse(ErrorCategory.FATAL.shouldContinueLoop());
    }
}
