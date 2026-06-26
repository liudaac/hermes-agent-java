package com.nousresearch.hermes.business.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps YAML snake_case payloads into {@link AgentTemplate}. */
final class AgentTemplateMapper {
    private AgentTemplateMapper() {}

    @SuppressWarnings("unchecked")
    static AgentTemplate fromMap(Map<String, Object> data) {
        AgentTemplate t = new AgentTemplate();
        t.setTemplateId(asString(data.get("template_id")));
        t.setName(asString(data.get("name")));
        t.setRole(asString(data.get("role")));
        t.setCategory(asString(data.get("category")));
        t.setStatus(parseStatus(asString(data.get("status"))));
        t.setIcon(asString(data.get("icon")));
        t.setColor(asString(data.get("color")));
        t.setMission(asString(data.get("mission")));
        t.setDescription(asString(data.get("description")));
        t.setSkills(asStringList(data.get("skills")));
        t.setMetrics(parseMetrics((List<Object>) data.get("metrics")));
        t.setAllowedTools(asStringList(data.get("allowed_tools")));
        t.setAllowedSkills(asStringList(data.get("allowed_skills")));
        t.setInstructions(asString(data.get("instructions")));

        Object handoff = data.get("handoff_policy");
        if (handoff instanceof Map) {
            t.setHandoffPolicy(parseHandoff((Map<String, Object>) handoff));
        }

        Object risk = data.get("risk_policy");
        if (risk instanceof Map) {
            Map<String, Object> rm = (Map<String, Object>) risk;
            AgentTemplate.RiskPolicy rp = new AgentTemplate.RiskPolicy();
            rp.setHigh(asStringList(rm.get("high")));
            rp.setMedium(asStringList(rm.get("medium")));
            rp.setLow(asStringList(rm.get("low")));
            t.setRiskPolicy(rp);
        }

        Object workflow = data.get("demo_workflow");
        if (workflow instanceof List) {
            List<AgentTemplate.WorkflowStep> steps = new ArrayList<>();
            for (Object item : (List<Object>) workflow) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> sm = (Map<String, Object>) item;
                AgentTemplate.WorkflowStep s = new AgentTemplate.WorkflowStep();
                s.setStep(asInt(sm.get("step"), 0));
                s.setActor(asString(sm.get("actor")));
                s.setAction(asString(sm.get("action")));
                s.setDuration(asString(sm.get("duration")));
                steps.add(s);
            }
            t.setDemoWorkflow(steps);
        }

        t.setRaw(new LinkedHashMap<>(data));
        return t;
    }

    @SuppressWarnings("unchecked")
    private static AgentTemplate.HandoffPolicy parseHandoff(Map<String, Object> map) {
        AgentTemplate.HandoffPolicy p = new AgentTemplate.HandoffPolicy();
        p.setDefaultTarget(asString(map.get("default_target")));
        Object triggers = map.get("triggers");
        if (triggers instanceof List) {
            List<AgentTemplate.HandoffTrigger> out = new ArrayList<>();
            for (Object item : (List<Object>) triggers) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> tm = (Map<String, Object>) item;
                out.add(new AgentTemplate.HandoffTrigger()
                    .setCondition(asString(tm.get("condition")))
                    .setTarget(asString(tm.get("target"))));
            }
            p.setTriggers(out);
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    static List<AgentTemplate.Metric> parseMetrics(List<Object> raw) {
        List<AgentTemplate.Metric> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object item : raw) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            AgentTemplate.Metric metric = new AgentTemplate.Metric();
            metric.setLabel(asString(m.get("label")));
            metric.setValue(asString(m.get("value")));
            metric.setUnit(asString(m.get("unit")));
            out.add(metric);
        }
        return out;
    }

    static AgentTemplate.Status parseStatus(String raw) {
        if (raw == null) return AgentTemplate.Status.STABLE;
        try {
            return AgentTemplate.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return AgentTemplate.Status.STABLE;
        }
    }

    static String asString(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    static List<String> asStringList(Object v) {
        if (v == null) return new ArrayList<>();
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return new ArrayList<>();
    }

    static int asInt(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
