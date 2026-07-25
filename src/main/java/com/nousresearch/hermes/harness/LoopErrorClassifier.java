package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Classifies exceptions and error strings into {@link ErrorCategory}.
 *
 * <p>The classifier uses exception type inspection and message pattern
 * matching to determine the appropriate recovery strategy. It is a
 * stateless utility class.</p>
 *
 * <h3>Classification rules</h3>
 *
 * <p><b>TRANSIENT</b> (retry with backoff):</p>
 * <ul>
 *   <li>{@link IOException}, {@link SocketTimeoutException},
 *       {@link ConnectException}, {@link TimeoutException}</li>
 *   <li>HTTP 429, 500, 502, 503, 504 in error message</li>
 *   <li>Messages containing: "rate limit", "timeout", "temporarily",
 *       "service unavailable", "connection refused", "reset by peer"</li>
 * </ul>
 *
 * <p><b>LLM_RECOVERABLE</b> (feed back to model):</p>
 * <ul>
 *   <li>JSON parse errors from model response</li>
 *   <li>Messages containing: "invalid tool call", "malformed",
 *       "parse error", "unexpected token", "missing required"</li>
 *   <li>IllegalArgumentException on tool call arguments</li>
 * </ul>
 *
 * <p><b>USER_FIXABLE</b> (structured pause):</p>
 * <ul>
 *   <li>Sandbox exceptions (file not found, permission denied)</li>
 *   <li>Quota exceeded exceptions</li>
 *   <li>Approval required (already handled separately, but listed)</li>
 *   <li>Messages containing: "permission denied", "not found",
 *       "quota exceeded", "unauthorized", "forbidden"</li>
 * </ul>
 *
 * <p><b>FATAL</b> (everything else):</p>
 *   Default fallback for unclassifiable errors.
 */
public class LoopErrorClassifier {
    private static final Logger logger = LoggerFactory.getLogger(LoopErrorClassifier.class);

    // Pattern-based detection for error messages (case-insensitive)
    private static final Pattern TRANSIENT_PATTERN = Pattern.compile(
        "(?i)(rate.?limit|429|timeout|timed.?out|temporarily|service.?unavailable|" +
        "connection.?refused|reset.?by.?peer|50[0234]|internal.?server|" +
        "bad.?gateway|gateway.?timeout|overloaded|capacity)"
    );

    private static final Pattern LLM_RECOVERABLE_PATTERN = Pattern.compile(
        "(?i)(invalid.?tool.?call|malformed|parse.?error|unexpected.?token|" +
        "missing.?required|illegal.?argument|bad.?request|400|" +
        "invalid.?json|syntax.?error|does.?not.?match)"
    );

    private static final Pattern USER_FIXABLE_PATTERN = Pattern.compile(
        "(?i)(permission.?denied|not.?found|no.?such.?file|quota.?exceeded|" +
        "unauthorized|forbidden|403|404|access.?denied|" +
        "insufficient|cannot.?access|read.?only|sandbox.?denied)"
    );

    private LoopErrorClassifier() {} // static utility

    /**
     * Classify an exception into an error category.
     *
     * @param e the exception to classify
     * @return the error category (never null)
     */
    public static ErrorCategory classify(Exception e) {
        if (e == null) return ErrorCategory.FATAL;

        // Approval required is handled separately in the loop
        if (isApprovalException(e)) return ErrorCategory.FATAL;

        // Type-based classification (most specific first)
        if (isTransientType(e)) return ErrorCategory.TRANSIENT;
        if (isUserFixableType(e)) return ErrorCategory.USER_FIXABLE;
        if (isLlmRecoverableType(e)) return ErrorCategory.LLM_RECOVERABLE;

        // Fall back to message-based classification
        String msg = e.getMessage();
        if (msg != null) {
            if (TRANSIENT_PATTERN.matcher(msg).find()) return ErrorCategory.TRANSIENT;
            if (LLM_RECOVERABLE_PATTERN.matcher(msg).find()) return ErrorCategory.LLM_RECOVERABLE;
            if (USER_FIXABLE_PATTERN.matcher(msg).find()) return ErrorCategory.USER_FIXABLE;
        }

        // Check cause chain (one level deep)
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            String causeMsg = cause.getMessage();
            if (causeMsg != null) {
                if (TRANSIENT_PATTERN.matcher(causeMsg).find()) return ErrorCategory.TRANSIENT;
                if (USER_FIXABLE_PATTERN.matcher(causeMsg).find()) return ErrorCategory.USER_FIXABLE;
                if (LLM_RECOVERABLE_PATTERN.matcher(causeMsg).find()) return ErrorCategory.LLM_RECOVERABLE;
            }
        }

        return ErrorCategory.FATAL;
    }

    /**
     * Classify an error string (e.g. from ChatCompletionResponse.getError()).
     *
     * @param error the error string
     * @return the error category (never null)
     */
    public static ErrorCategory classify(String error) {
        if (error == null || error.isBlank()) return ErrorCategory.FATAL;

        if (TRANSIENT_PATTERN.matcher(error).find()) return ErrorCategory.TRANSIENT;
        if (LLM_RECOVERABLE_PATTERN.matcher(error).find()) return ErrorCategory.LLM_RECOVERABLE;
        if (USER_FIXABLE_PATTERN.matcher(error).find()) return ErrorCategory.USER_FIXABLE;

        return ErrorCategory.FATAL;
    }

    // ==================== Type-based checks ====================

    private static boolean isApprovalException(Exception e) {
        return e.getClass().getSimpleName().contains("Approval")
            || e instanceof TenantAwareAIAgent.ToolApprovalRequiredException;
    }

    private static boolean isTransientType(Exception e) {
        return e instanceof SocketTimeoutException
            || e instanceof ConnectException
            || e instanceof TimeoutException
            || e instanceof java.net.http.HttpTimeoutException
            || (e instanceof IOException && !(e instanceof java.nio.file.NoSuchFileException));
    }

    private static boolean isUserFixableType(Exception e) {
        String name = e.getClass().getSimpleName();
        return name.contains("Sandbox")
            || name.contains("Quota")
            || name.contains("NotFound")
            || name.contains("Permission")
            || name.contains("Access")
            || name.contains("Unauthorized")
            || name.contains("Forbidden")
            || e instanceof java.nio.file.NoSuchFileException
            || e instanceof java.nio.file.AccessDeniedException
            || e instanceof SecurityException;
    }

    private static boolean isLlmRecoverableType(Exception e) {
        return e instanceof IllegalArgumentException
            || e instanceof NumberFormatException
            || e instanceof java.util.NoSuchElementException;
    }
}
