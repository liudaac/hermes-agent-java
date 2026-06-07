package com.nousresearch.hermes.org.market;

import java.time.Instant;
import java.util.*;

/**
 * Pre-packaged agent template for rapid deployment.
 * Templates encode an agent's role, tools, skills, prompts,
 * and governance settings — enabling one-click provisioning
 * of organization-approved agents.
 */
public class AgentTemplate {

    public enum Level { BASIC, STANDARD, PREMIUM, ENTERPRISE }

    private final String id;
    private final String name;
    private final String description;
    private final String category;
    private final String version;
    private final Level level;

    // Agent configuration
    private final String roleName;
    private final String roleDescription;
    private final String systemPrompt;
    private final Set<String> tools;
    private final Set<String> skills;
    private final Set<String> permissions;

    // Default governance
    private final long defaultTokenBudget;
    private final int defaultTimeoutSeconds;
    private final String defaultModel;

    // Metadata
    private final String author;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String icon;
    private final List<String> tags;

    // Usage stats
    private volatile int installCount;
    private volatile double rating;
    private volatile int ratingCount;

    // Template parameters (customizable on instantiation)
    private final Map<String, TemplateParam> params;

    private AgentTemplate(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.name = Objects.requireNonNull(builder.name, "name");
        this.description = builder.description != null ? builder.description : "";
        this.category = Objects.requireNonNull(builder.category, "category");
        this.version = builder.version != null ? builder.version : "1.0.0";
        this.level = builder.level != null ? builder.level : Level.STANDARD;
        this.roleName = Objects.requireNonNull(builder.roleName, "roleName");
        this.roleDescription = builder.roleDescription != null ? builder.roleDescription : "";
        this.systemPrompt = builder.systemPrompt != null ? builder.systemPrompt : "";
        this.tools = Set.copyOf(builder.tools);
        this.skills = Set.copyOf(builder.skills);
        this.permissions = Set.copyOf(builder.permissions);
        this.defaultTokenBudget = builder.defaultTokenBudget;
        this.defaultTimeoutSeconds = builder.defaultTimeoutSeconds;
        this.defaultModel = builder.defaultModel;
        this.author = builder.author != null ? builder.author : "system";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.icon = builder.icon;
        this.tags = List.copyOf(builder.tags);
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(builder.params));
    }

    /** Instantiate this template into a concrete agent configuration. */
    public Map<String, Object> instantiate(Map<String, String> paramValues) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("template_id", id);
        config.put("template_version", version);
        config.put("name", resolveParams(name, paramValues));
        config.put("role", resolveParams(roleName, paramValues));
        config.put("role_description", roleDescription);
        config.put("system_prompt", resolveParams(systemPrompt, paramValues));
        config.put("tools", new ArrayList<>(tools));
        config.put("skills", new ArrayList<>(skills));
        config.put("permissions", new ArrayList<>(permissions));
        config.put("token_budget", defaultTokenBudget);
        config.put("timeout_seconds", defaultTimeoutSeconds);
        config.put("model", defaultModel);
        config.put("level", level.name());
        config.put("instantiated_at", Instant.now().toString());
        return config;
    }

    private String resolveParams(String template, Map<String, String> values) {
        String result = template;
        for (var entry : values.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    // ---- stats ----

    public void recordInstall() { installCount++; }
    public void recordRating(double r) { 
        rating = (rating * ratingCount + r) / (ratingCount + 1);
        ratingCount++;
    }

    // ---- getters ----

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getVersion() { return version; }
    public Level getLevel() { return level; }
    public String getRoleName() { return roleName; }
    public String getRoleDescription() { return roleDescription; }
    public String getSystemPrompt() { return systemPrompt; }
    public Set<String> getTools() { return tools; }
    public Set<String> getSkills() { return skills; }
    public Set<String> getPermissions() { return permissions; }
    public long getDefaultTokenBudget() { return defaultTokenBudget; }
    public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
    public String getDefaultModel() { return defaultModel; }
    public String getAuthor() { return author; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getIcon() { return icon; }
    public List<String> getTags() { return tags; }
    public int getInstallCount() { return installCount; }
    public double getRating() { return rating; }
    public int getRatingCount() { return ratingCount; }
    public Map<String, TemplateParam> getParams() { return params; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("category", category);
        m.put("version", version);
        m.put("level", level.name());
        m.put("role", roleName);
        m.put("tools", tools);
        m.put("skills", skills);
        m.put("permissions", permissions);
        m.put("author", author);
        m.put("tags", tags);
        m.put("installs", installCount);
        m.put("rating", String.format("%.1f (%d)", rating, ratingCount));
        m.put("params", params.keySet());
        return m;
    }

    /** Customizable parameter in a template. */
    public record TemplateParam(String name, String description, String defaultValue, boolean required) {}

    public static class Builder {
        private String id;
        private String name;
        private String description;
        private String category;
        private String version = "1.0.0";
        private Level level = Level.STANDARD;
        private String roleName;
        private String roleDescription;
        private String systemPrompt;
        private final Set<String> tools = new LinkedHashSet<>();
        private final Set<String> skills = new LinkedHashSet<>();
        private final Set<String> permissions = new LinkedHashSet<>();
        private long defaultTokenBudget = 100_000;
        private int defaultTimeoutSeconds = 300;
        private String defaultModel = "gpt-4";
        private String author;
        private String icon;
        private final List<String> tags = new ArrayList<>();
        private final Map<String, TemplateParam> params = new LinkedHashMap<>();

        public Builder(String id, String name, String category, String roleName) {
            this.id = id; this.name = name; this.category = category; this.roleName = roleName;
        }

        public Builder description(String d) { this.description = d; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder level(Level l) { this.level = l; return this; }
        public Builder roleDescription(String d) { this.roleDescription = d; return this; }
        public Builder systemPrompt(String p) { this.systemPrompt = p; return this; }
        public Builder tools(String... ts) { Collections.addAll(tools, ts); return this; }
        public Builder skills(String... ss) { Collections.addAll(skills, ss); return this; }
        public Builder permissions(String... ps) { Collections.addAll(permissions, ps); return this; }
        public Builder tokenBudget(long b) { this.defaultTokenBudget = b; return this; }
        public Builder timeout(int s) { this.defaultTimeoutSeconds = s; return this; }
        public Builder model(String m) { this.defaultModel = m; return this; }
        public Builder author(String a) { this.author = a; return this; }
        public Builder icon(String i) { this.icon = i; return this; }
        public Builder tags(String... ts) { Collections.addAll(tags, ts); return this; }
        public Builder param(String name, String desc, String defaultVal, boolean required) {
            params.put(name, new TemplateParam(name, desc, defaultVal, required)); return this;
        }
        public AgentTemplate build() { return new AgentTemplate(this); }
    }

    // ---- well-known templates ----

    public static AgentTemplate codeReviewBot() {
        return new Builder("code-review", "Code Review Bot", "dev", "Code Reviewer")
            .description("Automated code review agent that checks PRs for bugs, style, and security issues.")
            .systemPrompt("You are a senior code reviewer. Check code for correctness, readability, security, and performance.")
            .roleDescription("Reviews pull requests and provides actionable feedback")
            .tools("file:read", "git:diff", "web:search")
            .skills("code-review", "security-scan")
            .permissions("file:read", "web:search", "git:*")
            .tags("dev", "code-review", "qa")
            .level(Level.STANDARD)
            .author("org-templates")
            .icon("🔍")
            .build();
    }

    public static AgentTemplate releaseManager() {
        return new Builder("release-manager", "Release Manager", "devops", "Release Manager")
            .description("Coordinates release pipelines, runs tests, manages changelogs, and triggers deployments.")
            .systemPrompt("You manage software releases. Verify changelogs, run CI/CD, coordinate with QA, and deploy.")
            .tools("terminal", "git:commit", "git:push", "git:tag")
            .skills("ci-cd", "changelog")
            .permissions("code:deploy", "git:*", "file:write", "code:review")
            .tags("devops", "release", "deploy")
            .level(Level.PREMIUM)
            .tokenBudget(500_000)
            .timeout(600)
            .author("org-templates")
            .icon("🚀")
            .build();
    }

    public static AgentTemplate customerSupport() {
        return new Builder("customer-support", "Customer Support Agent", "support", "Support Agent")
            .description("Handles customer inquiries, searches knowledge base, and escalates complex issues.")
            .systemPrompt("You are a helpful support agent. Answer customer questions using the knowledge base. Escalate when needed.")
            .tools("web:search", "file:read", "msg:send")
            .skills("faq", "customer-service")
            .permissions("web:search", "file:read", "data:read", "msg:send")
            .tags("support", "customer", "faq")
            .level(Level.BASIC)
            .author("org-templates")
            .icon("💬")
            .param("company_name", "Company name for greetings", "Our Company", true)
            .param("escalation_email", "Email for escalations", "support@company.com", true)
            .build();
    }

    public static AgentTemplate securityAuditor() {
        return new Builder("security-auditor", "Security Auditor", "security", "Security Auditor")
            .description("Scans infrastructure and code for vulnerabilities and compliance violations.")
            .systemPrompt("You are a security auditor. Scan for OWASP Top 10, CVE exposures, and compliance gaps.")
            .tools("terminal", "file:read", "web:search")
            .skills("security-scan", "compliance")
            .permissions("file:read", "web:search", "code:review")
            .tags("security", "compliance", "audit")
            .level(Level.PREMIUM)
            .tokenBudget(300_000)
            .author("org-templates")
            .icon("🛡️")
            .build();
    }

    public static AgentTemplate dataAnalyst() {
        return new Builder("data-analyst", "Data Analyst", "analytics", "Data Analyst")
            .description("Analyzes data, generates reports, and creates visualizations.")
            .systemPrompt("You are a data analyst. Query databases, analyze trends, and present findings.")
            .tools("execute_python", "file:read", "file:write", "data:read")
            .skills("data-analysis", "visualization")
            .permissions("data:read", "data:export", "file:write", "code:execute")
            .tags("analytics", "data", "reporting")
            .level(Level.STANDARD)
            .tokenBudget(200_000)
            .author("org-templates")
            .icon("📊")
            .build();
    }

    public static List<AgentTemplate> allBuiltin() {
        return List.of(codeReviewBot(), releaseManager(), customerSupport(), securityAuditor(), dataAnalyst());
    }
}
