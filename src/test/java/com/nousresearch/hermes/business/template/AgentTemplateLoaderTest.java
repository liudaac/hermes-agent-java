package com.nousresearch.hermes.business.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentTemplateLoaderTest {

    @Test
    void loadsAllShippedAgentTemplates() {
        BusinessTemplateService svc = new BusinessTemplateService();
        assertTrue(svc.listAgents().size() >= 14,
            "Expected at least 14 agent templates (HR 5 + Finance 5 + Assets 4), got " + svc.listAgents().size());

        assertEquals(5, svc.listAgents("hr").size(), "HR templates");
        AgentTemplate sourcer = svc.getAgent("hr-talent-sourcer").orElseThrow();
        assertEquals("招聘官", sourcer.getName());
        assertFalse(sourcer.getSkills().isEmpty());
        assertNotNull(sourcer.getRiskPolicy());
        assertFalse(sourcer.getRiskPolicy().getHigh().isEmpty());
        assertFalse(sourcer.getDemoWorkflow().isEmpty());

        assertEquals(5, svc.listAgents("finance").size(), "Finance templates");
        assertTrue(svc.getAgent("finance-ap-specialist").isPresent());

        assertEquals(4, svc.listAgents("assets").size(), "Assets templates");
        assertTrue(svc.getAgent("assets-asset-registrar").isPresent());
        assertTrue(svc.getAgent("assets-depreciation-advisor").isPresent());
    }

    @Test
    void loadsAllShippedScenarioTemplates() {
        BusinessTemplateService svc = new BusinessTemplateService();
        assertTrue(svc.listScenarios().size() >= 7,
            "Expected at least 7 scenarios (HR 2 + Finance 2 + Assets 2 + Cross-domain 1), got " + svc.listScenarios().size());
        ScenarioTemplate onboarding = svc.getScenario("hr-onboarding-7day").orElseThrow();
        assertEquals("7 天入职闭环", onboarding.getName());
        assertFalse(onboarding.getInvolvedAgents().isEmpty());

        ScenarioTemplate cross = svc.getScenario("x-new-employee-onboarding").orElseThrow();
        assertEquals("cross-domain", cross.getCategory());
        assertTrue(cross.getInvolvedAgents().size() >= 4,
            "Cross-domain scenario should pull from 3+ departments");
    }
}
