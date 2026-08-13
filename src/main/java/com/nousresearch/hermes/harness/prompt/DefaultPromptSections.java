package com.nousresearch.hermes.harness.prompt;

import com.nousresearch.hermes.config.Constants;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the default system prompt sections that were previously
 * hardcoded in {@code TenantAwareAIAgent.buildSystemPrompt()}.
 *
 * <p>Each section is registered with a conventional order:</p>
 * <ul>
 *   <li>{@code -100} - harness identity</li>
 *   <li>{@code 0} - memory guidance</li>
 *   <li>{@code 100} - tool-use enforcement</li>
 *   <li>{@code 110} - execution discipline</li>
 *   <li>{@code 120} - session search guidance</li>
 *   <li>{@code 130} - skills guidance</li>
 * </ul>
 */
public final class DefaultPromptSections {

    private DefaultPromptSections() {}

    /**
     * Register all default sections into the given assembler.
     * @return a composite disposer that unregisters all sections
     */
    public static Runnable registerAll(SystemPromptAssembler assembler) {
        List<Runnable> disposers = new ArrayList<>();

        disposers.add(assembler.registerSection(new PromptSection() {
            @Override public String name() { return "hermes:identity"; }
            @Override public int order() { return -100; }
            @Override public String render(PromptAssembleContext ctx) {
                return Constants.DEFAULT_AGENT_IDENTITY;
            }
        }));

        disposers.add(assembler.registerSection(new PromptSection() {
            @Override public String name() { return "hermes:memory-guidance"; }
            @Override public int order() { return 0; }
            @Override public String render(PromptAssembleContext ctx) {
                return Constants.MEMORY_GUIDANCE;
            }
        }));

        disposers.add(assembler.registerSection(new PromptSection() {
            @Override public String name() { return "hermes:tool-use-enforcement"; }
            @Override public int order() { return 100; }
            @Override public String render(PromptAssembleContext ctx) {
                return Constants.TOOL_USE_ENFORCEMENT_GUIDANCE;
            }
        }));

        disposers.add(assembler.registerSection(new PromptSection() {
            @Override public String name() { return "hermes:execution-discipline"; }
            @Override public int order() { return 110; }
            @Override public String render(PromptAssembleContext ctx) {
                return Constants.EXECUTION_DISCIPLINE_GUIDANCE;
            }
        }));

        disposers.add(assembler.registerSection(new PromptSection() {
            @Override public String name() { return "hermes:session-search"; }
            @Override public int order() { return 120; }
            @Override public String render(PromptAssembleContext ctx) {
                return Constants.SESSION_SEARCH_GUIDANCE;
            }
        }));

        disposers.add(assembler.registerSection(new PromptSection() {
            @Override public String name() { return "hermes:skills-guidance"; }
            @Override public int order() { return 130; }
            @Override public String render(PromptAssembleContext ctx) {
                return Constants.SKILLS_GUIDANCE;
            }
        }));

        return () -> {
            for (Runnable d : disposers) {
                try { d.run(); } catch (Exception ignored) {}
            }
        };
    }
}
