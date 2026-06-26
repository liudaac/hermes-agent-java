package com.nousresearch.hermes.business.template;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.collaboration.pattern.CollaborationPattern;
import com.nousresearch.hermes.prompt.PromptAssetRecord;
import com.nousresearch.hermes.prompt.PromptAssetService;
import com.nousresearch.hermes.scenario.ScenarioRecord;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Orchestrates "one-click clone" of a {@link ScenarioTemplate}:
 * workspace (created if missing) + prompt assets + team blueprint + scenario.
 */
public class TemplateCloneService {
    private static final Logger logger = LoggerFactory.getLogger(TemplateCloneService.class);

    private final BusinessTemplateService templateService;
    private final WorkspaceService workspaceService;
    private final PromptAssetService promptAssetService;
    private final TeamBlueprintService teamBlueprintService;
    private final ScenarioService scenarioService;

    public TemplateCloneService(BusinessTemplateService templateService,
                                WorkspaceService workspaceService,
                                PromptAssetService promptAssetService,
                                TeamBlueprintService teamBlueprintService,
                                ScenarioService scenarioService) {
        this.templateService = templateService;
        this.workspaceService = workspaceService;
        this.promptAssetService = promptAssetService;
        this.teamBlueprintService = teamBlueprintService;
        this.scenarioService = scenarioService;
    }

    public static class CloneResult {
        public final String workspaceId;
        public final String teamId;
        public final String scenarioId;
        public final List<String> promptAssetIds;
        public final boolean workspaceCreated;

        public CloneResult(String workspaceId, String teamId, String scenarioId,
                           List<String> promptAssetIds, boolean workspaceCreated) {
            this.workspaceId = workspaceId;
            this.teamId = teamId;
            this.scenarioId = scenarioId;
            this.promptAssetIds = promptAssetIds;
            this.workspaceCreated = workspaceCreated;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workspaceId", workspaceId);
            m.put("teamId", teamId);
            m.put("scenarioId", scenarioId);
            m.put("promptAssetIds", promptAssetIds);
            m.put("workspaceCreated", workspaceCreated);
            return m;
        }
    }

    public CloneResult clone(String templateId, CloneRequest request) {
        ScenarioTemplate template = templateService.getScenario(templateId)
            .orElseThrow(() -> new NoSuchElementException("Scenario template not found: " + templateId));

        if (request == null) request = new CloneRequest();
        String workspaceId = ensureWorkspace(template, request);

        List<AgentTemplate> agentTemplates = new ArrayList<>();
        for (ScenarioTemplate.InvolvedAgent ia : template.getInvolvedAgents()) {
            templateService.getAgent(ia.getTemplateId()).ifPresent(agentTemplates::add);
        }

        List<String> assetIds = new ArrayList<>();
        List<String> assetRefs = new ArrayList<>();
        ScenarioTemplate.CloneBlueprint blueprint = template.getCloneBlueprint();
        if (blueprint != null && blueprint.getPromptAssets() != null) {
            for (ScenarioTemplate.PromptAssetSpec spec : blueprint.getPromptAssets()) {
                String assetId = uniquePromptAssetId(workspaceId, spec.getAssetId());
                try {
                    PromptAssetRecord rec = promptAssetService.createPromptAsset(
                        workspaceId,
                        assetId,
                        spec.getName(),
                        spec.getPurpose(),
                        spec.getContent() != null ? spec.getContent() : "",
                        List.of("template", template.getTemplateId()),
                        templateMetadata(template));
                    assetIds.add(rec.getAssetId());
                    assetRefs.add("prompt://" + rec.getAssetId());
                } catch (Exception ex) {
                    logger.warn("Skipping prompt asset {}: {}", spec.getAssetId(), ex.getMessage());
                }
            }
        }

        String teamId = uniqueTeamId(workspaceId, deriveTeamId(template));
        String teamName = blueprint != null && blueprint.getTeam() != null && blueprint.getTeam().getName() != null
            ? blueprint.getTeam().getName()
            : template.getName();
        String teamDescription = blueprint != null && blueprint.getTeam() != null
            ? blueprint.getTeam().getDescription()
            : template.getSummary();

        List<AgentBlueprintRecord> agents = new ArrayList<>();
        for (AgentTemplate at : agentTemplates) {
            agents.add(toAgentBlueprintRecord(at));
        }

        String operatingManual = buildOperatingManual(template, agentTemplates);

        TeamBlueprintRecord team = teamBlueprintService.createTeamBlueprint(
            workspaceId,
            teamId,
            teamName,
            teamDescription,
            template.getName(),
            null,
            agents,
            assetRefs,
            operatingManual,
            templateMetadata(template));

        String scenarioId = uniqueScenarioId(workspaceId, deriveScenarioId(template));
        ScenarioTemplate.ScenarioSpec scenarioSpec = blueprint != null ? blueprint.getScenario() : null;
        String scenarioName = scenarioSpec != null && scenarioSpec.getName() != null
            ? scenarioSpec.getName()
            : template.getName();
        String scenarioDescription = scenarioSpec != null && scenarioSpec.getDescription() != null
            ? scenarioSpec.getDescription()
            : template.getSummary();
        List<String> successCriteria = scenarioSpec != null
            ? scenarioSpec.getSuccessCriteria()
            : new ArrayList<>();
        CollaborationPattern pattern = parseCollaborationPattern(
            scenarioSpec != null ? scenarioSpec.getCollaborationPattern() : null);

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId,
            scenarioId,
            scenarioName,
            scenarioDescription,
            team.getTeamId(),
            successCriteria,
            new ArrayList<>(),
            templateMetadata(template),
            pattern,
            null);

        logger.info("Cloned scenario template {} → workspace={} team={} scenario={}",
            template.getTemplateId(), workspaceId, team.getTeamId(), scenario.getScenarioId());

        return new CloneResult(workspaceId, team.getTeamId(), scenario.getScenarioId(),
            assetIds, request.workspaceCreatedThisCall);
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private String ensureWorkspace(ScenarioTemplate template, CloneRequest request) {
        String requestedId = request.workspaceId;
        if (requestedId != null && !requestedId.isBlank()
            && workspaceService.getWorkspace(requestedId).isPresent()) {
            return requestedId;
        }
        String workspaceId = requestedId != null && !requestedId.isBlank()
            ? requestedId
            : deriveWorkspaceId(template);
        String workspaceName = request.workspaceName != null && !request.workspaceName.isBlank()
            ? request.workspaceName
            : (template.getName() != null ? template.getName() : workspaceId);
        WorkspaceRecord rec = workspaceService.createWorkspace(
            workspaceId,
            workspaceName,
            template.getSummary(),
            request.owner != null ? request.owner : "business",
            templateMetadata(template));
        request.workspaceCreatedThisCall = true;
        return rec.getWorkspaceId();
    }

    private String deriveWorkspaceId(ScenarioTemplate template) {
        String base = template.getCategory() != null
            ? template.getCategory() + "-" + template.getTemplateId()
            : template.getTemplateId();
        return slug(base) + "-" + shortStamp();
    }

    private String deriveTeamId(ScenarioTemplate template) {
        return slug(template.getTemplateId()) + "-team";
    }

    private String deriveScenarioId(ScenarioTemplate template) {
        return slug(template.getTemplateId());
    }

    private String slug(String raw) {
        if (raw == null) return "team";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
    }

    private String shortStamp() {
        return Long.toString(Instant.now().toEpochMilli(), 36);
    }

    private String uniquePromptAssetId(String workspaceId, String baseId) {
        String id = slug(baseId);
        if (promptAssetService.getPromptAsset(workspaceId, id).isEmpty()) return id;
        return id + "-" + shortStamp();
    }

    private String uniqueTeamId(String workspaceId, String baseId) {
        String id = slug(baseId);
        if (teamBlueprintService.getTeamBlueprint(workspaceId, id).isEmpty()) return id;
        return id + "-" + shortStamp();
    }

    private String uniqueScenarioId(String workspaceId, String baseId) {
        String id = slug(baseId);
        if (scenarioService.getScenario(workspaceId, id).isEmpty()) return id;
        return id + "-" + shortStamp();
    }

    private AgentBlueprintRecord toAgentBlueprintRecord(AgentTemplate at) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("templateId", at.getTemplateId());
        metadata.put("role", at.getRole());
        metadata.put("category", at.getCategory());
        metadata.put("icon", at.getIcon());
        metadata.put("color", at.getColor());
        metadata.put("status", at.getStatus() != null ? at.getStatus().name() : null);
        if (at.getMission() != null) metadata.put("mission", at.getMission());
        if (at.getInstructions() != null) metadata.put("instructions", at.getInstructions());
        if (at.getDemoWorkflow() != null && !at.getDemoWorkflow().isEmpty()) {
            metadata.put("demoWorkflow",
                at.getDemoWorkflow().stream().map(AgentTemplate.WorkflowStep::toMap).toList());
        }

        List<String> approvalRules = new ArrayList<>();
        if (at.getRiskPolicy() != null) {
            for (String item : at.getRiskPolicy().getHigh()) approvalRules.add("HIGH: " + item);
            for (String item : at.getRiskPolicy().getMedium()) approvalRules.add("MEDIUM: " + item);
        }

        List<String> knowledgeRefs = new ArrayList<>();
        if (at.getSkills() != null) knowledgeRefs.addAll(at.getSkills());

        return new AgentBlueprintRecord()
            .setAgentId(slug(at.getTemplateId()))
            .setDisplayName(at.getName() != null ? at.getName() : at.getTemplateId())
            .setResponsibility(at.getMission() != null ? at.getMission() : at.getDescription())
            .setAllowedTools(new ArrayList<>(at.getAllowedTools()))
            .setAllowedSkills(new ArrayList<>(at.getAllowedSkills()))
            .setApprovalRules(approvalRules)
            .setKnowledgeRefs(knowledgeRefs)
            .setMetadata(metadata);
    }

    private String buildOperatingManual(ScenarioTemplate template, List<AgentTemplate> agents) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(template.getName() != null ? template.getName() : template.getTemplateId()).append("\n\n");
        if (template.getSummary() != null) sb.append(template.getSummary()).append("\n\n");
        if (template.getDescription() != null) sb.append(template.getDescription()).append("\n\n");
        if (!agents.isEmpty()) {
            sb.append("## 团队成员\n");
            for (AgentTemplate at : agents) {
                sb.append("- **").append(at.getName()).append("** (").append(at.getRole()).append(") — ")
                    .append(at.getMission() != null ? at.getMission() : "")
                    .append("\n");
            }
            sb.append("\n");
        }
        if (template.getWorkflowTimeline() != null && !template.getWorkflowTimeline().isEmpty()) {
            sb.append("## 协作时间线\n");
            for (ScenarioTemplate.TimelineEntry entry : template.getWorkflowTimeline()) {
                sb.append("- ").append(entry.getT() != null ? entry.getT() : "")
                    .append(" · ").append(entry.getActor() != null ? entry.getActor() : "")
                    .append(" → ").append(entry.getAction() != null ? entry.getAction() : "")
                    .append("\n");
            }
        }
        return sb.toString();
    }

    private Map<String, Object> templateMetadata(ScenarioTemplate template) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "template-clone");
        m.put("templateId", template.getTemplateId());
        if (template.getCategory() != null) m.put("templateCategory", template.getCategory());
        if (template.getIndustryTag() != null) m.put("industryTag", template.getIndustryTag());
        m.put("clonedAt", Instant.now().toString());
        return m;
    }

    private CollaborationPattern parseCollaborationPattern(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CollaborationPattern.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // Custom patterns from templates that aren't in the enum fall back to default (null → SEQUENTIAL).
            logger.debug("Unknown collaboration pattern in template: {}", raw);
            return null;
        }
    }
}