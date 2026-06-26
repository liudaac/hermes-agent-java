package com.nousresearch.hermes.business.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps YAML snake_case payloads into {@link ScenarioTemplate}. */
final class ScenarioTemplateMapper {
    private ScenarioTemplateMapper() {}

    @SuppressWarnings("unchecked")
    static ScenarioTemplate fromMap(Map<String, Object> data) {
        ScenarioTemplate t = new ScenarioTemplate();
        t.setTemplateId(AgentTemplateMapper.asString(data.get("template_id")));
        t.setName(AgentTemplateMapper.asString(data.get("name")));
        t.setCategory(AgentTemplateMapper.asString(data.get("category")));
        t.setStatus(parseStatus(AgentTemplateMapper.asString(data.get("status"))));
        t.setIndustryTag(AgentTemplateMapper.asString(data.get("industry_tag")));
        t.setIcon(AgentTemplateMapper.asString(data.get("icon")));
        t.setColor(AgentTemplateMapper.asString(data.get("color")));
        t.setSummary(AgentTemplateMapper.asString(data.get("summary")));
        t.setDescription(AgentTemplateMapper.asString(data.get("description")));
        t.setMetrics(AgentTemplateMapper.parseMetrics((List<Object>) data.get("metrics")));

        Object involved = data.get("involved_agents");
        if (involved instanceof List) {
            List<ScenarioTemplate.InvolvedAgent> out = new ArrayList<>();
            for (Object item : (List<Object>) involved) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                out.add(new ScenarioTemplate.InvolvedAgent()
                    .setTemplateId(AgentTemplateMapper.asString(m.get("template_id")))
                    .setRoleInScenario(AgentTemplateMapper.asString(m.get("role_in_scenario"))));
            }
            t.setInvolvedAgents(out);
        }

        Object cloneBp = data.get("clone_blueprint");
        if (cloneBp instanceof Map) {
            t.setCloneBlueprint(parseClone((Map<String, Object>) cloneBp));
        }

        Object timeline = data.get("workflow_timeline");
        if (timeline instanceof List) {
            List<ScenarioTemplate.TimelineEntry> out = new ArrayList<>();
            for (Object item : (List<Object>) timeline) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                out.add(new ScenarioTemplate.TimelineEntry()
                    .setT(AgentTemplateMapper.asString(m.get("t")))
                    .setActor(AgentTemplateMapper.asString(m.get("actor")))
                    .setAction(AgentTemplateMapper.asString(m.get("action"))));
            }
            t.setWorkflowTimeline(out);
        }

        t.setRaw(new LinkedHashMap<>(data));
        return t;
    }

    @SuppressWarnings("unchecked")
    private static ScenarioTemplate.CloneBlueprint parseClone(Map<String, Object> map) {
        ScenarioTemplate.CloneBlueprint c = new ScenarioTemplate.CloneBlueprint();
        Object team = map.get("team");
        if (team instanceof Map) {
            Map<String, Object> tm = (Map<String, Object>) team;
            c.setTeam(new ScenarioTemplate.TeamSpec()
                .setName(AgentTemplateMapper.asString(tm.get("name")))
                .setDescription(AgentTemplateMapper.asString(tm.get("description"))));
        }
        Object assets = map.get("prompt_assets");
        if (assets instanceof List) {
            List<ScenarioTemplate.PromptAssetSpec> out = new ArrayList<>();
            for (Object item : (List<Object>) assets) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                out.add(new ScenarioTemplate.PromptAssetSpec()
                    .setAssetId(AgentTemplateMapper.asString(m.get("asset_id")))
                    .setName(AgentTemplateMapper.asString(m.get("name")))
                    .setPurpose(AgentTemplateMapper.asString(m.get("purpose")))
                    .setContent(AgentTemplateMapper.asString(m.get("content"))));
            }
            c.setPromptAssets(out);
        }
        Object scenario = map.get("scenario");
        if (scenario instanceof Map) {
            Map<String, Object> sm = (Map<String, Object>) scenario;
            c.setScenario(new ScenarioTemplate.ScenarioSpec()
                .setName(AgentTemplateMapper.asString(sm.get("name")))
                .setDescription(AgentTemplateMapper.asString(sm.get("description")))
                .setCollaborationPattern(AgentTemplateMapper.asString(sm.get("collaboration_pattern")))
                .setSuccessCriteria(AgentTemplateMapper.asStringList(sm.get("success_criteria"))));
        }
        return c;
    }

    static ScenarioTemplate.Status parseStatus(String raw) {
        if (raw == null) return ScenarioTemplate.Status.STABLE;
        try {
            return ScenarioTemplate.Status.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ScenarioTemplate.Status.STABLE;
        }
    }
}
