package com.nousresearch.hermes.harness.prompt;

import java.util.*;

/**
 * The assembled system prompt, ready to be sent to the model.
 *
 * <p>Contains ordered sections (already rendered and interpolated),
 * resolved contexts, and the tool definitions visible for this assembly.</p>
 */
public class PromptAssembly {

    private final List<AssembledSection> sections;
    private final List<AssembledContext> contexts;
    private final Map<String, String> variables;

    public PromptAssembly(
        List<AssembledSection> sections,
        List<AssembledContext> contexts,
        Map<String, String> variables
    ) {
        this.sections = List.copyOf(sections);
        this.contexts = List.copyOf(contexts);
        this.variables = Map.copyOf(variables);
    }

    /** Render the full system prompt by concatenating all sections. */
    public String renderSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        for (AssembledSection s : sections) {
            if (!s.text().isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(s.text());
            }
        }
        return sb.toString();
    }

    /** Render all contexts as a single user message, or empty if none. */
    public String renderContexts() {
        StringBuilder sb = new StringBuilder();
        for (AssembledContext c : contexts) {
            if (!c.text().isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(c.text());
            }
        }
        return sb.toString();
    }

    public List<AssembledSection> sections() { return sections; }
    public List<AssembledContext> contexts() { return contexts; }
    public Map<String, String> variables() { return variables; }

    /** One resolved section. */
    public record AssembledSection(String name, String text) {}
    /** One resolved context. */
    public record AssembledContext(String name, String text) {}
}
