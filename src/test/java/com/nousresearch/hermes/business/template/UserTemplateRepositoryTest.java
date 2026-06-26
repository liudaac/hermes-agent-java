package com.nousresearch.hermes.business.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UserTemplateRepositoryTest {
    @TempDir
    Path tmp;

    @Test
    void uploadsAndListsCustomAgentTemplate() {
        BusinessTemplateService svc = new BusinessTemplateService(tmp);
        UserTemplateRepository repo = new UserTemplateRepository(svc);

        String yaml = """
            template_id: custom-recruiter
            name: 资深招聘官
            role: Senior Sourcer
            category: hr
            icon: user-search
            color: orange
            mission: 高阶招聘漏斗管理
            skills:
              - 高管搜寻
              - 猎头网络
              - Offer 谈判
            metrics:
              - label: 平均交付周期
                value: "30d"
            allowed_tools: []
            allowed_skills: []
            risk_policy:
              high:
                - 跨主体调动
              medium: []
              low:
                - 候选人搜寻
            """;

        AgentTemplate uploaded = repo.uploadAgent(yaml, "alice@example.com");
        assertEquals("custom-recruiter", uploaded.getTemplateId());
        assertEquals("hr", uploaded.getCategory());

        // The new template should be visible in the live catalog
        assertTrue(svc.getAgent("custom-recruiter").isPresent());
        assertTrue(svc.listAgents("hr").stream()
            .anyMatch(a -> a.getTemplateId().equals("custom-recruiter")));

        // Repository should list it with author metadata
        var items = repo.listUserTemplates();
        assertEquals(1, items.size());
        assertEquals("agent", items.get(0).get("type"));
        assertEquals("custom-recruiter", items.get(0).get("templateId"));

        // Delete by id removes it
        assertTrue(repo.deleteByTemplateId("custom-recruiter"));
        assertFalse(svc.getAgent("custom-recruiter").isPresent(),
            "Template should disappear after deletion");
    }

    @Test
    void rejectsInvalidTemplate() {
        BusinessTemplateService svc = new BusinessTemplateService(tmp);
        UserTemplateRepository repo = new UserTemplateRepository(svc);
        assertThrows(IllegalArgumentException.class, () -> repo.uploadAgent("", "anon"));
        // Missing required fields
        assertThrows(IllegalArgumentException.class,
            () -> repo.uploadAgent("template_id: x\nname: Y\n", "anon"));
    }
}
