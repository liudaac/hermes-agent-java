package com.nousresearch.hermes.business.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogisticsTemplateTest {

    @Test
    void shipsFiveLogisticsAgents() {
        BusinessTemplateService svc = new BusinessTemplateService();
        assertEquals(5, svc.listAgents("logistics").size(), "Logistics agent count");
        assertTrue(svc.getAgent("logistics-shipment-dispatcher").isPresent());
        assertTrue(svc.getAgent("logistics-tracking-agent").isPresent());
        assertTrue(svc.getAgent("logistics-csr").isPresent());
        assertTrue(svc.getAgent("logistics-exception-handler").isPresent());
        assertTrue(svc.getAgent("logistics-analyst").isPresent());
    }

    @Test
    void ecommerceReturnIsCrossDomain() {
        BusinessTemplateService svc = new BusinessTemplateService();
        ScenarioTemplate cross = svc.getScenario("x-ecommerce-return").orElseThrow();
        assertEquals("cross-domain", cross.getCategory());
        // Should touch logistics + finance
        java.util.Set<String> cats = new java.util.HashSet<>();
        for (var ia : cross.getInvolvedAgents()) {
            svc.getAgent(ia.getTemplateId()).ifPresent(a -> cats.add(a.getCategory()));
        }
        assertTrue(cats.contains("logistics"), "Should touch logistics");
        assertTrue(cats.contains("finance"), "Should touch finance");
    }

    @Test
    void totalShippedTemplates() {
        BusinessTemplateService svc = new BusinessTemplateService();
        // 5 HR + 5 finance + 4 assets + 5 logistics = 19
        assertEquals(19, svc.listAgents().size(), "Total agent count");
        // HR 2 + finance 2 + assets 2 + logistics 2 + cross-domain 2 = 10
        assertTrue(svc.listScenarios().size() >= 10,
            "Total scenarios should be >= 10, got " + svc.listScenarios().size());
    }
}
