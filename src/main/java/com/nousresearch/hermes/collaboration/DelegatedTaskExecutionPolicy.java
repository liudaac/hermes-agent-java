package com.nousresearch.hermes.collaboration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe execution constraints for delegated-task executor adapters.
 *
 * <p>This policy is intentionally conservative. The default does not permit
 * real external execution; adapters can inspect the policy and return a
 * structured not-executed result instead of launching processes or mutating
 * files. Parent verification constraints are carried alongside execution
 * constraints so future APIs can execute-and-submit through one service
 * boundary.</p>
 */
public record DelegatedTaskExecutionPolicy(
    boolean allowExternalExecution,
    boolean allowFileChanges,
    boolean allowCommands,
    Duration timeout,
    List<String> allowedChangedFilePrefixes,
    ParentVerificationPolicy parentVerificationPolicy,
    Map<String, Object> metadata
) {
    public DelegatedTaskExecutionPolicy {
        timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        allowedChangedFilePrefixes = allowedChangedFilePrefixes == null ? List.of() : allowedChangedFilePrefixes.stream()
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .toList();
        parentVerificationPolicy = parentVerificationPolicy != null
            ? parentVerificationPolicy
            : new ParentVerificationPolicy(true, true, allowedChangedFilePrefixes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static DelegatedTaskExecutionPolicy safeDefault() {
        return new DelegatedTaskExecutionPolicy(false, false, false, Duration.ofMinutes(5), List.of(), ParentVerificationPolicy.strict(), Map.of());
    }

    public static DelegatedTaskExecutionPolicy mockOnly() {
        return new DelegatedTaskExecutionPolicy(false, false, false, Duration.ofSeconds(30), List.of(), ParentVerificationPolicy.strict(), Map.of("mode", "mock"));
    }

    public DelegatedTaskExecutionPolicy withParentVerificationPolicy(ParentVerificationPolicy policy) {
        return new DelegatedTaskExecutionPolicy(
            allowExternalExecution,
            allowFileChanges,
            allowCommands,
            timeout,
            allowedChangedFilePrefixes,
            policy,
            metadata
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allow_external_execution", allowExternalExecution);
        m.put("allow_file_changes", allowFileChanges);
        m.put("allow_commands", allowCommands);
        m.put("timeout_ms", timeout.toMillis());
        m.put("allowed_changed_file_prefixes", allowedChangedFilePrefixes);
        m.put("parent_verification_policy", parentVerificationPolicy.toMap());
        m.put("metadata", metadata);
        return m;
    }
}
