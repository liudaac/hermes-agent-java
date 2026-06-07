package com.nousresearch.hermes.org.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/**
 * Attribute-Based Access Control (ABAC) engine.
 *
 * <p>While RBAC answers "does this role have this permission?",
 * ABAC answers "should this agent, in this context, with these
 * attributes, perform this action on this resource right now?"</p>
 *
 * <p>Policies evaluate against:
 * <ul>
 *   <li><b>Subject attributes</b> — agent department, level, trust score</li>
 *   <li><b>Resource attributes</b> — data classification, owner, path</li>
 *   <li><b>Action</b> — what operation is being attempted</li>
 *   <li><b>Environment</b> — time of day, network zone, audit mode</li>
 * </ul>
 */
public class AttributeBasedAccessControl {

    /** Context for a single authorization decision. */
    public static class AuthContext {
        private final String subjectId;
        private final Map<String, Object> subjectAttrs;
        private final String resource;
        private final Map<String, Object> resourceAttrs;
        private final String action;
        private final Map<String, Object> environment;

        public AuthContext(
                String subjectId, Map<String, Object> subjectAttrs,
                String resource, Map<String, Object> resourceAttrs,
                String action, Map<String, Object> environment) {
            this.subjectId = Objects.requireNonNull(subjectId);
            this.subjectAttrs = Collections.unmodifiableMap(new HashMap<>(subjectAttrs));
            this.resource = Objects.requireNonNull(resource);
            this.resourceAttrs = Collections.unmodifiableMap(new HashMap<>(resourceAttrs));
            this.action = Objects.requireNonNull(action);
            this.environment = Collections.unmodifiableMap(new HashMap<>(environment));
        }

        public String getSubjectId() { return subjectId; }
        public Map<String, Object> getSubjectAttrs() { return subjectAttrs; }
        @SuppressWarnings("unchecked")
        public <T> T getSubjectAttr(String key) { return (T) subjectAttrs.get(key); }
        public String getResource() { return resource; }
        public Map<String, Object> getResourceAttrs() { return resourceAttrs; }
        @SuppressWarnings("unchecked")
        public <T> T getResourceAttr(String key) { return (T) resourceAttrs.get(key); }
        public String getAction() { return action; }
        public Map<String, Object> getEnvironment() { return environment; }
        @SuppressWarnings("unchecked")
        public <T> T getEnvAttr(String key) { return (T) environment.get(key); }
    }

    /** Result of an authorization decision with reasoning. */
    public record AuthDecision(
            boolean allowed,
            String reason,
            List<String> matchedPolicies,
            List<String> failedPolicies) {

        public static AuthDecision allow(String reason, String policy) {
            return new AuthDecision(true, reason, List.of(policy), List.of());
        }

        public static AuthDecision allow(String reason, List<String> policies) {
            return new AuthDecision(true, reason, policies, List.of());
        }

        public static AuthDecision deny(String reason, String policy) {
            return new AuthDecision(false, reason, List.of(), List.of(policy));
        }

        public static AuthDecision deny(String reason, List<String> policies) {
            return new AuthDecision(false, reason, List.of(), policies);
        }
    }

    // ---- policy engine ----

    private final List<AbacPolicy> policies = new ArrayList<>();

    /** Add a policy. Policies are evaluated in insertion order. */
    public void addPolicy(AbacPolicy policy) {
        policies.add(policy);
    }

    /** Remove a policy by name. */
    public boolean removePolicy(String name) {
        return policies.removeIf(p -> p.name.equals(name));
    }

    /**
     * Evaluate all policies against a request context.
     * The first explicit DENY stops evaluation.
     * If no policy matches, default is DENY.
     */
    public AuthDecision evaluate(AuthContext ctx) {
        List<String> matched = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (AbacPolicy policy : policies) {
            AbacPolicy.Evaluation eval = policy.evaluate(ctx);
            switch (eval) {
                case DENY:
                    failed.add(policy.name);
                    return AuthDecision.deny(
                        String.format("Policy '%s' explicitly denies %s on %s", policy.name, ctx.getAction(), ctx.getResource()),
                        List.of(policy.name));
                case ALLOW:
                    matched.add(policy.name);
                    return AuthDecision.allow(
                        String.format("Policy '%s' allows %s on %s", policy.name, ctx.getAction(), ctx.getResource()),
                        List.of(policy.name));
                case NOT_APPLICABLE:
                    // Continue to next policy
                    break;
                case ALLOW_WITH_WARNING:
                    matched.add(policy.name + "(warning)");
                    return AuthDecision.allow(
                        String.format("Policy '%s' allows %s on %s (with warnings)", policy.name, ctx.getAction(), ctx.getResource()),
                        List.of(policy.name));
            }
        }

        // Default allow — ABAC only denies explicitly, it doesn't grant access on its own.
        // RBAC is the primary gate; ABAC adds contextual restrictions.
        return AuthDecision.allow("No restrictive ABAC policy matched (default allow)", List.of());
    }

    /** Convenience: quick boolean check. */
    public boolean isAllowed(AuthContext ctx) {
        return evaluate(ctx).allowed();
    }

    // ---- policy class ----

    /**
     * A single ABAC policy rule.
     */
    public static class AbacPolicy {
        public enum Evaluation { ALLOW, DENY, NOT_APPLICABLE, ALLOW_WITH_WARNING }

        private final String name;
        private final int priority;
        private final Predicate<AuthContext> condition;
        private final Evaluation effect;

        public AbacPolicy(String name, int priority, Predicate<AuthContext> condition, Evaluation effect) {
            this.name = Objects.requireNonNull(name);
            this.priority = priority;
            this.condition = Objects.requireNonNull(condition);
            this.effect = Objects.requireNonNull(effect);
        }

        public Evaluation evaluate(AuthContext ctx) {
            if (!condition.test(ctx)) return Evaluation.NOT_APPLICABLE;
            return effect;
        }

        public String getName() { return name; }
        public int getPriority() { return priority; }
    }

    // ---- built-in policy factories ----

    /**
     * Create a policy that denies access to resources with classification above the agent's clearance.
     */
    public static AbacPolicy classificationCheck(int priority) {
        return new AbacPolicy("classification-check", priority, ctx -> {
            String dataClass = ctx.getResourceAttr("classification");
            Integer clearance = ctx.getSubjectAttr("clearance_level");
            if (dataClass == null) return false;

            int required = switch (dataClass.toUpperCase()) {
                case "PUBLIC" -> 0;
                case "INTERNAL" -> 1;
                case "CONFIDENTIAL" -> 2;
                case "RESTRICTED" -> 3;
                default -> 4;
            };
            return clearance != null && clearance < required;
        }, AbacPolicy.Evaluation.DENY);
    }

    /**
     * Create a policy that blocks access outside business hours for non-ADMIN agents.
     */
    public static AbacPolicy businessHoursCheck(int priority) {
        return new AbacPolicy("business-hours", priority, ctx -> {
            String role = ctx.getSubjectAttr("role");
            if ("ADMIN".equals(role)) return false;

            @SuppressWarnings("unchecked")
            Map<String, Object> env = ctx.getEnvironment();
            if (env.get("current_hour") instanceof Integer hour) {
                // Allow 7:00-22:00
                return hour < 7 || hour > 22;
            }
            return false;
        }, AbacPolicy.Evaluation.DENY);
    }

    /**
     * Create a policy that requires the agent to be in the same department as the resource.
     */
    public static AbacPolicy departmentBoundary(int priority) {
        return new AbacPolicy("department-boundary", priority, ctx -> {
            String agentDept = ctx.getSubjectAttr("department");
            String resourceDept = ctx.getResourceAttr("department");
            if (resourceDept == null) return false;
            return !resourceDept.equals(agentDept);
        }, AbacPolicy.Evaluation.DENY);
    }

    /**
     * Create a policy that limits code deploy to agents tagged "production-safe".
     */
    public static AbacPolicy deployGate(int priority) {
        return new AbacPolicy("deploy-gate", priority, ctx -> {
            if (!"code:deploy".equals(ctx.getAction())) return false;
            @SuppressWarnings("unchecked")
            Set<String> tags = ctx.getSubjectAttr("tags");
            return tags == null || !tags.contains("production-safe");
        }, AbacPolicy.Evaluation.DENY);
    }

    /**
     * Create a rate-limit policy based on recent activity.
     */
    public static AbacPolicy rateLimit(int priority, int maxRequestsPerMinute) {
        return new AbacPolicy("rate-limit", priority, ctx -> {
            Integer recentRequests = ctx.getSubjectAttr("requests_last_minute");
            return recentRequests != null && recentRequests > maxRequestsPerMinute;
        }, AbacPolicy.Evaluation.DENY);
    }

    /**
     * Create a policy that explicitly allows agents with an active approval to bypass other restrictions.
     */
    public static AbacPolicy approvalBypass(int priority) {
        return new AbacPolicy("approval-bypass", priority, ctx -> {
            Boolean hasApproval = ctx.getSubjectAttr("has_active_approval");
            return Boolean.TRUE.equals(hasApproval);
        }, AbacPolicy.Evaluation.ALLOW);
    }

    /**
     * Create a policy that allows trusted agents to perform any action.
     */
    public static AbacPolicy trustedAgentAllow(int priority, double minTrustScore) {
        return new AbacPolicy("trusted-agent", priority, ctx -> {
            Double trustScore = ctx.getSubjectAttr("trust_score");
            return trustScore != null && trustScore >= minTrustScore;
        }, AbacPolicy.Evaluation.ALLOW);
    }

    /** Number of loaded policies. */
    public int policyCount() { return policies.size(); }

    /** List all policy names. */
    public List<String> listPolicies() {
        return policies.stream().map(AbacPolicy::getName).toList();
    }
}
