package com.nousresearch.hermes.org.compliance;

import java.time.Instant;
import java.util.*;

/**
 * Organization compliance framework for AI agents.
 *
 * <p>Implements controls required by common regulatory frameworks
 * and maps agent behaviors to compliance evidence:</p>
 * <ul>
 *   <li><b>SOC 2</b> — security, availability, confidentiality</li>
 *   <li><b>ISO 27001</b> — information security management</li>
 *   <li><b>GDPR</b> — data protection, right to explanation</li>
 *   <li><b>AI Act</b> — risk classification, transparency</li>
 * </ul>
 */
public class ComplianceFramework {

    public enum Framework { SOC2, ISO27001, GDPR, EU_AI_ACT, HIPAA, PCI_DSS }

    public enum RiskLevel { MINIMAL, LOW, MEDIUM, HIGH, UNACCEPTABLE }

    // ---- data residency -------

    /** Data residency rules: data type → allowed regions. */
    private final Map<String, Set<String>> residencyRules = new LinkedHashMap<>();

    /** Where each agent's output is stored/processed. */
    private final Map<String, String> agentDataRegions = new LinkedHashMap<>();

    public void addResidencyRule(String dataType, String... regions) {
        residencyRules.put(dataType, Set.of(regions));
    }

    public void setAgentRegion(String agentId, String region) {
        agentDataRegions.put(agentId, region);
    }

    /** Check if an agent's data region complies with residency rules. */
    public boolean checkResidency(String agentId, String dataType) {
        Set<String> allowed = residencyRules.get(dataType);
        if (allowed == null) return true;
        String region = agentDataRegions.get(agentId);
        return region != null && allowed.contains(region);
    }

    // ---- risk assessment -------

    /** Assess an agent's risk level. */
    public RiskAssessment assessRisk(String agentId, String role, Set<String> permissions,
                                      String dataAccess, boolean humanInLoop) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (permissions.contains("code:deploy")) { score += 3; reasons.add("Can deploy code"); }
        if (permissions.contains("data:delete")) { score += 2; reasons.add("Can delete data"); }
        if (permissions.contains("data:export")) { score += 2; reasons.add("Can export data"); }
        if (permissions.contains("file:execute")) { score += 3; reasons.add("Can execute arbitrary code"); }
        if ("RESTRICTED".equals(dataAccess)) { score += 2; reasons.add("Accesses restricted data"); }
        if (!humanInLoop) { score += 2; reasons.add("No human-in-the-loop"); }

        RiskLevel level = switch (score / 2) {
            case 0 -> RiskLevel.MINIMAL;
            case 1 -> RiskLevel.LOW;
            case 2, 3 -> RiskLevel.MEDIUM;
            case 4 -> RiskLevel.HIGH;
            default -> RiskLevel.UNACCEPTABLE;
        };

        return new RiskAssessment(agentId, role, level, score, reasons, Instant.now());
    }

    // ---- control checks -------

    /** Run compliance controls against an agent's configuration. */
    public ComplianceReport runControls(String agentId, Map<String, Object> agentConfig,
                                         List<Framework> frameworks) {
        List<ControlResult> results = new ArrayList<>();

        for (Framework fw : frameworks) {
            results.addAll(runFrameworkChecks(fw, agentConfig));
        }

        long passed = results.stream().filter(ControlResult::passed).count();
        return new ComplianceReport(agentId, frameworks, results, passed, Instant.now());
    }

    private List<ControlResult> runFrameworkChecks(Framework fw, Map<String, Object> config) {
        List<ControlResult> results = new ArrayList<>();

        // Universal controls (apply to all frameworks)
        boolean hasAuditLog = Boolean.TRUE.equals(config.get("audit_enabled"));
        boolean hasApproval = Boolean.TRUE.equals(config.get("approval_enabled"));
        boolean hasQuota = Boolean.TRUE.equals(config.get("quota_enabled"));
        @SuppressWarnings("unchecked")
        Set<String> permissions = config.get("permissions") instanceof Set ?
            (Set<String>) config.get("permissions") : Set.of();

        results.add(new ControlResult(fw, "audit-logging", hasAuditLog,
            hasAuditLog ? "Audit logging enabled" : "Missing audit logging"));
        results.add(new ControlResult(fw, "approval-required", hasApproval,
            hasApproval ? "Approval system enabled" : "Missing approval system"));
        results.add(new ControlResult(fw, "resource-limits", hasQuota,
            hasQuota ? "Resource quotas configured" : "Missing resource quotas"));
        results.add(new ControlResult(fw, "least-privilege",
            !permissions.contains("file:execute") && !permissions.contains("data:delete"),
            "Principle of least privilege check"));

        // Framework-specific checks
        if (fw == Framework.GDPR) {
            boolean dataMinimization = Boolean.TRUE.equals(config.get("data_minimization"));
            boolean rightToDelete = Boolean.TRUE.equals(config.get("right_to_delete"));
            results.add(new ControlResult(fw, "data-minimization", dataMinimization,
                dataMinimization ? "Data minimization configured" : "No data minimization"));
            results.add(new ControlResult(fw, "right-to-delete", rightToDelete,
                rightToDelete ? "Deletion workflow configured" : "No deletion workflow"));
        }

        if (fw == Framework.EU_AI_ACT) {
            boolean transparency = Boolean.TRUE.equals(config.get("transparency_mode"));
            boolean humanOversight = Boolean.TRUE.equals(config.get("human_oversight"));
            results.add(new ControlResult(fw, "transparency", transparency,
                transparency ? "Outputs labeled as AI-generated" : "No transparency labeling"));
            results.add(new ControlResult(fw, "human-oversight", humanOversight,
                humanOversight ? "Human oversight enabled" : "No human oversight"));
        }

        return results;
    }

    // ---- explainability -------

    /** Generate an explainability report for an agent's decision. */
    public String generateExplainabilityReport(String agentId, String decision, List<String> factors,
                                                List<String> alternatives, double confidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AI Decision Explainability Report ===\n");
        sb.append("Agent: ").append(agentId).append("\n");
        sb.append("Decision: ").append(decision).append("\n");
        sb.append("Confidence: ").append(String.format("%.0f%%", confidence * 100)).append("\n\n");

        sb.append("Factors considered:\n");
        for (String f : factors) sb.append("  • ").append(f).append("\n");

        sb.append("\nAlternatives evaluated:\n");
        for (String a : alternatives) sb.append("  • ").append(a).append("\n");

        sb.append("\nRisk Level: ").append(assessRisk(agentId, "unknown", Set.of(), "", false).level());
        sb.append("\nReport generated: ").append(Instant.now()).append("\n");
        return sb.toString();
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("residency_rules", residencyRules.size());
        s.put("agents_with_regions", agentDataRegions.size());
        return s;
    }

    // ---- inner types -------

    public record RiskAssessment(String agentId, String role, RiskLevel level, int score,
                                  List<String> reasons, Instant timestamp) {}

    public record ControlResult(Framework framework, String control, boolean passed, String detail) {}

    public record ComplianceReport(String agentId, List<Framework> frameworks,
                                    List<ControlResult> results, long passed, Instant timestamp) {
        public double passRate() { return results.isEmpty() ? 0 : (double) passed / results.size(); }
        public boolean isCompliant() { return passRate() >= 0.8; }
    }
}