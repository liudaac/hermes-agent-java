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


}
