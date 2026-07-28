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

    // ==================== Cause chain ====================

    // ==================== ErrorCategory methods ====================

}
