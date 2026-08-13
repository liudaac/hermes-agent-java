package com.nousresearch.hermes.harness.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Registry and assembler for system prompt sections, contexts, and variables.
 *
 * <p>Supports two layers:</p>
 * <ul>
 *   <li><b>Global</b> - visible to every agent in this process</li>
 *   <li><b>Agent-scoped</b> - owned by one agent, shadows globals with the same name</li>
 * </ul>
 *
 * <p>The assembler collects all registered sections/contexts/variables,
 * resolves them for one assembly, interpolates {@code {{variable}}} references,
 * and returns a {@link PromptAssembly}.</p>
 *
 * <p>This replaces the old hardcoded {@code buildSystemPrompt()} method
 * in {@code TenantAwareAIAgent}. Each module (Evolution, Team, Memory,
 * Jarvis, etc.) registers its own sections independently.</p>
 */
public class SystemPromptAssembler {
    private static final Logger logger = LoggerFactory.getLogger(SystemPromptAssembler.class);

    /** Reserved variable name pattern. */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    /** A complete {{...}} reference group. */
    private static final Pattern GROUP_AT = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

    // Global registrations
    private final Map<String, PromptSection> globalSections = new ConcurrentHashMap<>();
    private final Map<String, PromptContext> globalContexts = new ConcurrentHashMap<>();
    private final Map<String, PromptVariable> globalVariables = new ConcurrentHashMap<>();

    // Agent-scoped registrations (agentId -> registrations)
    private final Map<String, ScopeLayer> agentScopes = new ConcurrentHashMap<>();

    /**
     * Register a global prompt section.
     * @return disposer to unregister
     */
    public Runnable registerSection(PromptSection section) {
        if (globalSections.containsKey(section.name())) {
            throw new IllegalStateException(
                "prompt section \"" + section.name() + "\" is already registered globally");
        }
        globalSections.put(section.name(), section);
        return () -> globalSections.remove(section.name());
    }

    /**
     * Register a global prompt context.
     * @return disposer to unregister
     */
    public Runnable registerContext(PromptContext context) {
        if (globalContexts.containsKey(context.name())) {
            throw new IllegalStateException(
                "prompt context \"" + context.name() + "\" is already registered globally");
        }
        globalContexts.put(context.name(), context);
        return () -> globalContexts.remove(context.name());
    }

    /**
     * Register a global prompt variable.
     * @return disposer to unregister
     */
    public Runnable registerVariable(PromptVariable variable) {
        String name = variable.name();
        if (!VARIABLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "invalid prompt variable name \"" + name + "\" (must match [a-z][a-z0-9_]*)");
        }
        if (globalVariables.containsKey(name)) {
            throw new IllegalStateException(
                "prompt variable \"" + name + "\" is already registered globally");
        }
        globalVariables.put(name, variable);
        return () -> globalVariables.remove(name);
    }

    /**
     * Register an agent-scoped section that shadows a global with the same name.
     * @return disposer to unregister
     */
    public Runnable registerSection(String agentId, PromptSection section) {
        ScopeLayer scope = agentScopes.computeIfAbsent(agentId, k -> new ScopeLayer());
        if (scope.sections.containsKey(section.name())) {
            throw new IllegalStateException(
                "prompt section \"" + section.name() + "\" is already registered in scope " + agentId);
        }
        scope.sections.put(section.name(), section);
        return () -> {
            scope.sections.remove(section.name());
            if (scope.isEmpty()) agentScopes.remove(agentId);
        };
    }

    /**
     * Register an agent-scoped context.
     * @return disposer to unregister
     */
    public Runnable registerContext(String agentId, PromptContext context) {
        ScopeLayer scope = agentScopes.computeIfAbsent(agentId, k -> new ScopeLayer());
        if (scope.contexts.containsKey(context.name())) {
            throw new IllegalStateException(
                "prompt context \"" + context.name() + "\" is already registered in scope " + agentId);
        }
        scope.contexts.put(context.name(), context);
        return () -> {
            scope.contexts.remove(context.name());
            if (scope.isEmpty()) agentScopes.remove(agentId);
        };
    }

    /**
     * Register an agent-scoped variable.
     * @return disposer to unregister
     */
    public Runnable registerVariable(String agentId, PromptVariable variable) {
        String name = variable.name();
        if (!VARIABLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "invalid prompt variable name \"" + name + "\"");
        }
        ScopeLayer scope = agentScopes.computeIfAbsent(agentId, k -> new ScopeLayer());
        if (scope.variables.containsKey(name)) {
            throw new IllegalStateException(
                "prompt variable \"" + name + "\" is already registered in scope " + agentId);
        }
        scope.variables.put(name, variable);
        return () -> {
            scope.variables.remove(name);
            if (scope.isEmpty()) agentScopes.remove(agentId);
        };
    }

    /**
     * Assemble the system prompt for one agent.
     *
     * @param ctx the assembly context
     * @return the assembled prompt
     */
    public PromptAssembly assemble(PromptAssembleContext ctx) {
        ScopeLayer scope = ctx.agentId() != null ? agentScopes.get(ctx.agentId()) : null;

        // 1. Collect sections: global + agent-scoped (scope shadows global)
        Map<String, PromptSection> effectiveSections = new LinkedHashMap<>(globalSections);
        if (scope != null) {
            for (var e : scope.sections.entrySet()) {
                effectiveSections.put(e.getKey(), e.getValue());
            }
        }

        // 2. Collect contexts
        Map<String, PromptContext> effectiveContexts = new LinkedHashMap<>(globalContexts);
        if (scope != null) {
            for (var e : scope.contexts.entrySet()) {
                effectiveContexts.put(e.getKey(), e.getValue());
            }
        }

        // 3. Collect variables
        Map<String, PromptVariable> effectiveVariables = new LinkedHashMap<>(globalVariables);
        if (scope != null) {
            for (var e : scope.variables.entrySet()) {
                effectiveVariables.put(e.getKey(), e.getValue());
            }
        }

        // 4. Resolve variables
        Map<String, String> resolvedVars = new HashMap<>();
        for (var e : effectiveVariables.entrySet()) {
            String value = e.getValue().resolve(ctx);
            if (value != null) {
                resolvedVars.put(e.getKey(), value);
            }
        }

        // 5. Render sections (sorted by order)
        List<PromptSection> sortedSections = new ArrayList<>(effectiveSections.values());
        sortedSections.sort(Comparator.comparingInt(PromptSection::order));

        List<PromptAssembly.AssembledSection> assembledSections = new ArrayList<>();
        PromptSection completeSection = null;
        for (PromptSection s : sortedSections) {
            if (s.complete()) {
                completeSection = s;
            }
            String text = renderAndInterpolate(s.render(ctx), resolvedVars, "section", s.name());
            assembledSections.add(new PromptAssembly.AssembledSection(s.name(), text));
        }

        // If a complete section exists, it becomes the sole section
        if (completeSection != null) {
            String completeText = renderAndInterpolate(
                completeSection.render(ctx), resolvedVars, "section", completeSection.name());
            assembledSections = List.of(
                new PromptAssembly.AssembledSection(completeSection.name(), completeText));
        }

        // 6. Render contexts (sorted by order)
        List<PromptContext> sortedContexts = new ArrayList<>(effectiveContexts.values());
        sortedContexts.sort(Comparator.comparingInt(PromptContext::order));

        List<PromptAssembly.AssembledContext> assembledContexts = new ArrayList<>();
        for (PromptContext c : sortedContexts) {
            String text = renderAndInterpolate(c.render(ctx), resolvedVars, "context", c.name());
            if (!text.isEmpty()) {
                assembledContexts.add(new PromptAssembly.AssembledContext(c.name(), text));
            }
        }

        return new PromptAssembly(assembledSections, assembledContexts, resolvedVars);
    }

    /**
     * Interpolate {{variable}} references in the given text.
     */
    private String renderAndInterpolate(
        String text, Map<String, String> variables, String kind, String ownerName
    ) {
        if (text == null || text.isEmpty()) return "";
        var matcher = GROUP_AT.matcher(text);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            result.append(text, last, matcher.start());
            String name = matcher.group(1);
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                    "malformed prompt variable reference {{" + name + "}} in " + kind + " \"" + ownerName + "\"");
            }
            if (!variables.containsKey(name)) {
                // Unknown variable - leave as-is rather than failing
                logger.debug("Unknown prompt variable \"{}\" in {} \"{}\" - leaving as-is", name, kind, ownerName);
                result.append(matcher.group());
                last = matcher.end();
                continue;
            }
            String value = variables.get(name);
            result.append(value != null ? value : "");
            last = matcher.end();
        }
        result.append(text.substring(last));
        return result.toString();
    }

    /** Check if any sections are registered. */
    public boolean hasSections() {
        return !globalSections.isEmpty() || agentScopes.values().stream().anyMatch(s -> !s.sections.isEmpty());
    }

    /** One agent-scoped registration layer. */
    private static class ScopeLayer {
        final Map<String, PromptSection> sections = new LinkedHashMap<>();
        final Map<String, PromptContext> contexts = new LinkedHashMap<>();
        final Map<String, PromptVariable> variables = new LinkedHashMap<>();

        boolean isEmpty() {
            return sections.isEmpty() && contexts.isEmpty() && variables.isEmpty();
        }
    }
}
