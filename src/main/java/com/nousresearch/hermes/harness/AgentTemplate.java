package com.nousresearch.hermes.harness;

import java.util.Set;

/**
 * Predefined specialist agent template for agent-as-tool pattern.
 *
 * <p>Each template defines a complete configuration for a specialized
 * sub-agent: system prompt, tool whitelist, and iteration limit.
 * Templates are looked up by name when the {@code agent_delegate} tool
 * is invoked.</p>
 *
 * <h3>Built-in templates</h3>
 * <ul>
 *   <li>{@link #CODE_REVIEWER} - reviews code for bugs, style, security</li>
 *   <li>{@link #TEST_WRITER} - generates test cases and test code</li>
 *   <li>{@link #RESEARCHER} - web research and information gathering</li>
 *   <li>{@link #ANALYZER} - data/log analysis with pattern detection</li>
 *   <li>{@link #PLANNER} - task decomposition and planning</li>
 * </ul>
 */
public record AgentTemplate(
    String name,
    String description,
    String systemPrompt,
    Set<String> toolWhitelist,
    int maxIterations,
    ForkMode defaultForkMode
) {

    // ==================== Built-in Templates ====================

    public static final AgentTemplate CODE_REVIEWER = new AgentTemplate(
        "code_reviewer",
        "Reviews code for bugs, security issues, style violations, and improvement opportunities",
        """
        You are a senior code reviewer. Your job is to review code thoroughly.

        Focus on:
        - Bugs and logic errors
        - Security vulnerabilities (injection, path traversal, etc.)
        - Performance issues
        - Code style and readability
        - Missing error handling
        - Edge cases

        Read the relevant files, trace the logic, and provide a structured review
        with specific line references and concrete fix suggestions.
        """,
        Set.of("read_file", "search_files", "execute_command"),
        10,
        ForkMode.FULL
    );

    public static final AgentTemplate TEST_WRITER = new AgentTemplate(
        "test_writer",
        "Generates comprehensive test cases and test code",
        """
        You are a test engineer. Your job is to write high-quality tests.

        Focus on:
        - Unit tests for core logic
        - Edge cases and boundary conditions
        - Error path coverage
        - Integration test scenarios

        Read the source code first, understand the interfaces, then write tests.
        Use the project's existing test framework.
        """,
        Set.of("read_file", "write_file", "search_files", "execute_command"),
        15,
        ForkMode.FULL
    );

    public static final AgentTemplate RESEARCHER = new AgentTemplate(
        "researcher",
        "Performs web research and gathers information on a topic",
        """
        You are a research analyst. Your job is to find and synthesize information.

        Focus on:
        - Searching multiple sources for comprehensive coverage
        - Cross-referencing facts
        - Identifying key findings and patterns
        - Providing source URLs for verification

        Be thorough but concise. Return a structured summary with citations.
        """,
        Set.of("web_search", "web_extract", "read_file"),
        10,
        ForkMode.CLEAN
    );

    public static final AgentTemplate ANALYZER = new AgentTemplate(
        "analyzer",
        "Analyzes data, logs, or system output for patterns and insights",
        """
        You are a data analyst. Your job is to analyze data and extract insights.

        Focus on:
        - Pattern detection and trends
        - Anomaly identification
        - Root cause analysis
        - Actionable recommendations

        Use commands to inspect data files, parse logs, and run analysis scripts.
        Return findings in a structured format with evidence.
        """,
        Set.of("read_file", "search_files", "execute_command"),
        8,
        ForkMode.COMPRESSED
    );

    public static final AgentTemplate PLANNER = new AgentTemplate(
        "planner",
        "Decomposes complex tasks into an actionable plan with dependencies",
        """
        You are a project planner. Your job is to break down complex tasks.

        Focus on:
        - Task decomposition into manageable steps
        - Dependency identification
        - Risk assessment
        - Success criteria for each step

        Return a structured plan with clear steps, dependencies, and estimated effort.
        """,
        Set.of("read_file", "search_files"),
        5,
        ForkMode.COMPRESSED
    );

    // ==================== Registry ====================

    private static final java.util.Map<String, AgentTemplate> REGISTRY = java.util.Map.of(
        "code_reviewer", CODE_REVIEWER,
        "test_writer", TEST_WRITER,
        "researcher", RESEARCHER,
        "analyzer", ANALYZER,
        "planner", PLANNER
    );

    /**
     * Look up a template by name (case-insensitive).
     *
     * @return the template, or null if not found
     */
    public static AgentTemplate find(String name) {
        if (name == null) return null;
        return REGISTRY.get(name.toLowerCase().trim());
    }

    /**
     * List all available template names.
     */
    public static Set<String> availableTemplates() {
        return REGISTRY.keySet();
    }

    /**
     * Check if a template exists by name.
     */
    public static boolean exists(String name) {
        return find(name) != null;
    }
}
