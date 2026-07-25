package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentTemplateTest {

    @Test
    void builtinTemplatesExist() {
        assertNotNull(AgentTemplate.find("code_reviewer"));
        assertNotNull(AgentTemplate.find("test_writer"));
        assertNotNull(AgentTemplate.find("researcher"));
        assertNotNull(AgentTemplate.find("analyzer"));
        assertNotNull(AgentTemplate.find("planner"));
    }

    @Test
    void findIsCaseInsensitive() {
        assertEquals(AgentTemplate.CODE_REVIEWER, AgentTemplate.find("CODE_REVIEWER"));
        assertEquals(AgentTemplate.RESEARCHER, AgentTemplate.find(" Researcher "));
    }

    @Test
    void findReturnsNullForUnknown() {
        assertNull(AgentTemplate.find("nonexistent"));
        assertNull(AgentTemplate.find(null));
        assertNull(AgentTemplate.find(""));
    }

    @Test
    void existsWorks() {
        assertTrue(AgentTemplate.exists("code_reviewer"));
        assertFalse(AgentTemplate.exists("nonexistent"));
    }

    @Test
    void availableTemplatesListsAll() {
        var templates = AgentTemplate.availableTemplates();
        assertTrue(templates.contains("code_reviewer"));
        assertTrue(templates.contains("test_writer"));
        assertTrue(templates.contains("researcher"));
        assertTrue(templates.contains("analyzer"));
        assertTrue(templates.contains("planner"));
        assertEquals(5, templates.size());
    }

    @Test
    void templatesHaveRequiredFields() {
        for (String name : AgentTemplate.availableTemplates()) {
            AgentTemplate t = AgentTemplate.find(name);
            assertNotNull(t);
            assertNotNull(t.name());
            assertNotNull(t.description());
            assertNotNull(t.systemPrompt());
            assertNotNull(t.toolWhitelist());
            assertFalse(t.toolWhitelist().isEmpty(), "Template " + name + " should have tools");
            assertTrue(t.maxIterations() > 0, "Template " + name + " should have iterations");
            assertNotNull(t.defaultForkMode());
        }
    }

    @Test
    void codeReviewerForksFull() {
        assertEquals(ForkMode.FULL, AgentTemplate.CODE_REVIEWER.defaultForkMode());
    }

    @Test
    void researcherForksClean() {
        assertEquals(ForkMode.CLEAN, AgentTemplate.RESEARCHER.defaultForkMode());
    }

    @Test
    void analyzerForksCompressed() {
        assertEquals(ForkMode.COMPRESSED, AgentTemplate.ANALYZER.defaultForkMode());
    }
}
