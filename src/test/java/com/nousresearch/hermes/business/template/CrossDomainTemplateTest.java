package com.nousresearch.hermes.business.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrossDomainTemplateTest {

    @Test
    void newEmployeeOnboardingTouchesThreeDepartments() {
        BusinessTemplateService svc = new BusinessTemplateService();
        ScenarioTemplate cross = svc.getScenario("x-new-employee-onboarding").orElseThrow();
        assertEquals("cross-domain", cross.getCategory());
        assertNotNull(cross.getCloneBlueprint());

        List<String> categories = cross.getInvolvedAgents().stream()
            .map(a -> svc.getAgent(a.getTemplateId()).map(AgentTemplate::getCategory).orElse(""))
            .distinct()
            .toList();
        assertTrue(categories.contains("hr"), "Should include HR roles");
        assertTrue(categories.contains("finance"), "Should include Finance roles");
        assertTrue(categories.contains("assets"), "Should include Assets roles");
        assertTrue(cross.getWorkflowTimeline().size() >= 6,
            "Cross-domain timeline should be detailed");
    }

    @Test
    void assetsScenarioReferencesAssetsAgents() {
        BusinessTemplateService svc = new BusinessTemplateService();
        ScenarioTemplate quarterly = svc.getScenario("assets-quarterly-inventory").orElseThrow();
        boolean hasAssetsAgent = quarterly.getInvolvedAgents().stream()
            .anyMatch(a -> svc.getAgent(a.getTemplateId())
                .map(t -> "assets".equals(t.getCategory()))
                .orElse(false));
        assertTrue(hasAssetsAgent, "Quarterly inventory must reference an assets-category agent");
    }
}
