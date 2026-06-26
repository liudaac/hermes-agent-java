package com.nousresearch.hermes.business.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentTemplateLoaderTest {

    @Test
    void loadsAllShippedAgentTemplates() {
        BusinessTemplateService svc = new BusinessTemplateService();
        assertTrue(svc.listAgents().size() >= 10,
            "Expected at least 10 agent templates, got " + svc.listAgents().size());

        // HR coverage
        assertEquals(5, svc.listAgents("hr").size(), "HR templates");
        assertTrue(svc.getAgent("hr-talent-sourcer").isPresent());
        AgentTemplate sourcer = svc.getAgent("hr-talent-sourcer").orElseThrow();
        assertEquals("招聘官", sourcer.getName());
        assertEquals("hr", sourcer.getCategory());
        assertFalse(sourcer.getSkills().isEmpty());
        assertNotNull(sourcer.getRiskPolicy());
        assertFalse(sourcer.getRiskPolicy().getHigh().isEmpty());
        assertFalse(sourcer.getDemoWorkflow().isEmpty());

        // Finance coverage
        assertEquals(5, svc.listAgents("finance").size(), "Finance templates");
        assertTrue(svc.getAgent("finance-ap-specialist").isPresent());
    }

    @Test
    void loadsAllShippedScenarioTemplates() {
        BusinessTemplateService svc = new BusinessTemplateService();
        assertTrue(svc.listScenarios().size() >= 4);
        ScenarioTemplate onboarding = svc.getScenario("hr-onboarding-7day").orElseThrow();
        assertEquals("7 天入职闭环", onboarding.getName());
        assertFalse(onboarding.getInvolvedAgents().isEmpty());
        assertNotNull(onboarding.getCloneBlueprint());
        assertFalse(onboarding.getCloneBlueprint().getPromptAssets().isEmpty());
        assertFalse(onboarding.getWorkflowTimeline().isEmpty());
    }
}
