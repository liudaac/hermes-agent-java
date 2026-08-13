package com.nousresearch.hermes.harness.prompt;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SystemPromptAssemblerTest {

    private SystemPromptAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new SystemPromptAssembler();
    }

    @Test
    @DisplayName("Sections are rendered in order")
    void sectionsRenderedInOrder() {
        assembler.registerSection(section("c", 30, "C-content"));
        assembler.registerSection(section("a", 10, "A-content"));
        assembler.registerSection(section("b", 20, "B-content"));

        PromptAssembly assembly = assembler.assemble(ctx());
        String prompt = assembly.renderSystemPrompt();

        assertEquals("A-content\n\nB-content\n\nC-content", prompt);
    }

    @Test
    @DisplayName("Agent-scoped section shadows global with same name")
    void scopedSectionShadowsGlobal() {
        assembler.registerSection(section("persona", 0, "Global persona"));
        assembler.registerSection("agent-1", section("persona", 0, "Scoped persona"));

        // Agent with scope
        PromptAssembly scoped = assembler.assemble(ctx("agent-1"));
        assertEquals("Scoped persona", scoped.renderSystemPrompt());

        // Agent without scope
        PromptAssembly unscoped = assembler.assemble(ctx("agent-2"));
        assertEquals("Global persona", unscoped.renderSystemPrompt());
    }

    @Test
    @DisplayName("Complete section replaces all others")
    void completeSectionReplacesAll() {
        assembler.registerSection(section("identity", -100, "Identity"));
        assembler.registerSection(section("persona", 0, "Persona"));
        assembler.registerSection(new PromptSection() {
            @Override public String name() { return "custom"; }
            @Override public int order() { return 50; }
            @Override public String render(PromptAssembleContext ctx) { return "Custom complete"; }
            @Override public boolean complete() { return true; }
        });

        PromptAssembly assembly = assembler.assemble(ctx());
        assertEquals("Custom complete", assembly.renderSystemPrompt());
        assertEquals(1, assembly.sections().size());
        assertEquals("custom", assembly.sections().get(0).name());
    }

    @Test
    @DisplayName("Variable interpolation in sections")
    void variableInterpolation() {
        assembler.registerVariable(var("project", "Hermes"));
        assembler.registerSection(section("intro", 0, "Welcome to {{project}}!"));

        PromptAssembly assembly = assembler.assemble(ctx());
        assertEquals("Welcome to Hermes!", assembly.renderSystemPrompt());
    }

    @Test
    @DisplayName("Unknown variable left as-is")
    void unknownVariableLeftAsIs() {
        assembler.registerSection(section("intro", 0, "Hello {{unknown}}!"));

        PromptAssembly assembly = assembler.assemble(ctx());
        assertEquals("Hello {{unknown}}!", assembly.renderSystemPrompt());
    }

    @Test
    @DisplayName("Contexts rendered in order, empty skipped")
    void contextsRenderedInOrder() {
        assembler.registerContext(context("empty", 10, ""));
        assembler.registerContext(context("memory", 20, "Memory snapshot"));
        assembler.registerContext(context("env", 30, "Env info"));

        PromptAssembly assembly = assembler.assemble(ctx());
        String contexts = assembly.renderContexts();

        assertEquals("Memory snapshot\n\nEnv info", contexts);
    }

    @Test
    @DisplayName("Agent-scoped variable shadows global")
    void scopedVariableShadowsGlobal() {
        assembler.registerVariable(var("role", "default-agent"));
        assembler.registerVariable("agent-1", var("role", "special-agent"));

        assembler.registerSection(section("role", 0, "You are a {{role}}."));

        PromptAssembly scoped = assembler.assemble(ctx("agent-1"));
        assertEquals("You are a special-agent.", scoped.renderSystemPrompt());

        PromptAssembly unscoped = assembler.assemble(ctx("agent-2"));
        assertEquals("You are a default-agent.", unscoped.renderSystemPrompt());
    }

    @Test
    @DisplayName("Duplicate section name throws")
    void duplicateSectionThrows() {
        assembler.registerSection(section("a", 10, "A"));
        assertThrows(IllegalStateException.class, () ->
            assembler.registerSection(section("a", 20, "A2")));
    }

    @Test
    @DisplayName("Disposer unregisters section")
    void disposerUnregisters() {
        Runnable disposer = assembler.registerSection(section("a", 10, "A"));
        assertFalse(assembler.assemble(ctx()).sections().isEmpty());

        disposer.run();
        assertTrue(assembler.assemble(ctx()).sections().isEmpty());
    }

    @Test
    @DisplayName("Default sections register in correct order")
    void defaultSectionsRegisterCorrectly() {
        Runnable disposer = DefaultPromptSections.registerAll(assembler);
        PromptAssembly assembly = assembler.assemble(ctx());
        String prompt = assembly.renderSystemPrompt();

        assertTrue(prompt.contains("Hermes Agent"));
        assertTrue(prompt.contains("persistent memory"));
        assertTrue(prompt.contains("Tool-use enforcement"));
        assertTrue(prompt.contains("Execution discipline"));

        // Verify order: identity before memory before tool-use
        int identityIdx = prompt.indexOf("Hermes Agent");
        int memoryIdx = prompt.indexOf("persistent memory");
        int toolUseIdx = prompt.indexOf("Tool-use enforcement");
        assertTrue(identityIdx < memoryIdx);
        assertTrue(memoryIdx < toolUseIdx);

        disposer.run();
    }

    @Test
    @DisplayName("Invalid variable name throws")
    void invalidVariableNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            assembler.registerVariable(var("InvalidName", "value")));
        assertThrows(IllegalArgumentException.class, () ->
            assembler.registerVariable(var("123name", "value")));
    }

    // ===== Helpers =====

    private PromptAssembleContext ctx() {
        return ctx(null);
    }

    private PromptAssembleContext ctx(String agentId) {
        return new PromptAssembleContext("tenant-1", "session-1", agentId, null);
    }

    private PromptSection section(String name, int order, String text) {
        return new PromptSection() {
            @Override public String name() { return name; }
            @Override public int order() { return order; }
            @Override public String render(PromptAssembleContext ctx) { return text; }
        };
    }

    private PromptContext context(String name, int order, String text) {
        return new PromptContext() {
            @Override public String name() { return name; }
            @Override public int order() { return order; }
            @Override public String render(PromptAssembleContext ctx) { return text; }
        };
    }

    private PromptVariable var(String name, String value) {
        return new PromptVariable() {
            @Override public String name() { return name; }
            @Override public String resolve(PromptAssembleContext ctx) { return value; }
        };
    }
}
