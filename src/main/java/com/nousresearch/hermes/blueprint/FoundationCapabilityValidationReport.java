package com.nousresearch.hermes.blueprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Foundation-aware validation report for Business Portal design-time artifacts.
 *
 * <p>The report is intentionally non-mutating: it does not create tenants,
 * register tools, compile teams or execute anything. It only records whether a
 * Business Portal blueprint can be grounded in existing Hermes foundation
 * capabilities.</p>
 */
public class FoundationCapabilityValidationReport {
    private String workspaceId;
    private String tenantId;
    private String teamId;
    private Integer version;
    private Instant validatedAt = Instant.now();
    private boolean valid = true;
    private final List<Finding> findings = new ArrayList<>();

    public String getWorkspaceId() { return workspaceId; }
    public FoundationCapabilityValidationReport setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getTenantId() { return tenantId; }
    public FoundationCapabilityValidationReport setTenantId(String tenantId) { this.tenantId = tenantId; return this; }
    public String getTeamId() { return teamId; }
    public FoundationCapabilityValidationReport setTeamId(String teamId) { this.teamId = teamId; return this; }
    public Integer getVersion() { return version; }
    public FoundationCapabilityValidationReport setVersion(Integer version) { this.version = version; return this; }
    public Instant getValidatedAt() { return validatedAt; }
    public boolean isValid() { return valid; }
    public List<Finding> getFindings() { return Collections.unmodifiableList(findings); }

    public boolean hasErrors() {
        return findings.stream().anyMatch(finding -> finding.severity() == Severity.ERROR);
    }

    public boolean hasWarnings() {
        return findings.stream().anyMatch(finding -> finding.severity() == Severity.WARNING);
    }

    public FoundationCapabilityValidationReport info(String code, String message, String path, Map<String, Object> details) {
        return add(Severity.INFO, code, message, path, details);
    }

    public FoundationCapabilityValidationReport warning(String code, String message, String path, Map<String, Object> details) {
        return add(Severity.WARNING, code, message, path, details);
    }

    public FoundationCapabilityValidationReport error(String code, String message, String path, Map<String, Object> details) {
        return add(Severity.ERROR, code, message, path, details);
    }

    private FoundationCapabilityValidationReport add(Severity severity, String code, String message, String path, Map<String, Object> details) {
        findings.add(new Finding(severity, code, message, path, details != null ? Map.copyOf(details) : Map.of()));
        if (severity == Severity.ERROR) {
            valid = false;
        }
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workspaceId", workspaceId);
        map.put("tenantId", tenantId);
        map.put("teamId", teamId);
        map.put("version", version);
        map.put("validatedAt", validatedAt.toString());
        map.put("valid", valid);
        map.put("findings", findings.stream().map(Finding::toMap).toList());
        return map;
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public record Finding(
        Severity severity,
        String code,
        String message,
        String path,
        Map<String, Object> details
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("severity", severity.name());
            map.put("code", code);
            map.put("message", message);
            map.put("path", path);
            map.put("details", details != null ? details : Map.of());
            return map;
        }
    }
}
