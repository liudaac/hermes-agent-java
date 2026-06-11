package com.nousresearch.hermes.collaboration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A structured reason that a delegated executor request is unsafe.
 */
public record ExecutorSafetyViolation(
    String code,
    String message,
    String subject
) {
    public ExecutorSafetyViolation {
        code = code == null || code.isBlank() ? "SAFETY_VIOLATION" : code;
        message = message == null ? "" : message;
        subject = subject == null ? "" : subject;
    }

    public static ExecutorSafetyViolation of(String code, String message, String subject) {
        return new ExecutorSafetyViolation(code, message, subject);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("subject", subject);
        return m;
    }
}
