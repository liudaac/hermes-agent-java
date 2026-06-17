package com.nousresearch.hermes.prompt;

import com.nousresearch.hermes.org.knowledge.OrganizationalKnowledgeBase;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantSkill;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves Business Portal prompt refs and enriches them with existing Hermes
 * foundation context.
 *
 * <p>Prompt assets remain a business-managed prompt/SOP facade. Memory, skills
 * and organizational knowledge remain separate foundation sources.</p>
 */
public class PromptAssetResolver implements FoundationPromptAssetBridge {
    private final PromptAssetService promptAssetService;
    private final WorkspaceService workspaceService;
    private final TenantManager tenantManager;

    public PromptAssetResolver(PromptAssetService promptAssetService, WorkspaceService workspaceService, TenantManager tenantManager) {
        this.promptAssetService = Objects.requireNonNull(promptAssetService, "promptAssetService");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.tenantManager = Objects.requireNonNull(tenantManager, "tenantManager");
    }

    @Override
    public boolean exists(String workspaceId, String assetId, Integer version) {
        try {
            promptAssetService.requireVersion(workspaceId, assetId, version);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public PromptContext resolve(String workspaceId, List<String> refs) {
        return resolve(workspaceId, refs, null, ResolveOptions.promptOnly());
    }

    public PromptContext resolve(String workspaceId, List<String> refs, String taskContext, ResolveOptions options) {
        ResolveOptions effective = options != null ? options : ResolveOptions.promptOnly();
        WorkspaceRecord workspace = workspaceService.requireWorkspace(workspaceId);
        PromptContext context = new PromptContext()
            .setWorkspaceId(workspaceId)
            .meta("source", "business-prompt-resolver")
            .meta("tenantId", workspace.getTenantId());

        resolvePromptRefs(context, workspaceId, refs);
        if (effective.includeFoundationContext()) {
            TenantContext tenant = tenantManager.getTenant(workspace.getTenantId());
            if (tenant == null) {
                context.warning("tenant_missing: cannot include memory/skills/org knowledge for tenant " + workspace.getTenantId());
            } else {
                includeMemory(context, tenant, effective);
                includeSkills(context, tenant, taskContext, effective);
                includeOrgKnowledge(context, tenant, taskContext, effective);
            }
        }
        return context;
    }

    private void resolvePromptRefs(PromptContext context, String workspaceId, List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            context.warning("prompt_refs_empty");
            return;
        }
        for (String ref : refs) {
            PromptRef parsed = parse(ref);
            if (parsed == null) {
                context.warning("prompt_ref_invalid: " + ref);
                continue;
            }
            try {
                PromptAssetRecord asset = promptAssetService.requirePromptAsset(workspaceId, parsed.assetId());
                PromptAssetVersion version = promptAssetService.requireVersion(workspaceId, parsed.assetId(), parsed.version());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("assetId", asset.getAssetId());
                metadata.put("assetName", asset.getName());
                metadata.put("purpose", asset.getPurpose());
                metadata.put("version", version.getVersion());
                metadata.put("status", version.getStatus());
                metadata.put("tags", asset.getTags() != null ? asset.getTags() : List.of());
                context.addSegment(new PromptContext.Segment(
                    "business-prompt-asset",
                    canonicalRef(asset.getAssetId(), version.getVersion()),
                    asset.getName() + " #v" + version.getVersion(),
                    version.getContent() != null ? version.getContent() : "",
                    metadata
                ));
            } catch (RuntimeException e) {
                context.warning("prompt_ref_missing: " + ref + " (" + e.getMessage() + ")");
            }
        }
    }

    private void includeMemory(PromptContext context, TenantContext tenant, ResolveOptions options) {
        if (!options.includeMemory()) return;
        String snapshot = tenant.getMemoryManager().getSystemPromptSnapshot();
        if (snapshot != null && !snapshot.isBlank()) {
            context.addSegment(new PromptContext.Segment(
                "foundation-memory",
                "memory://tenant-system-prompt",
                "Tenant Memory Context",
                truncate(snapshot, options.maxCharsPerFoundationSegment()),
                Map.of("tenantId", tenant.getTenantId())
            ));
        }
    }

    private void includeSkills(PromptContext context, TenantContext tenant, String taskContext, ResolveOptions options) {
        if (!options.includeSkills()) return;
        List<TenantSkill> skills = tenant.getSkillManager().listAvailableSkills().stream()
            .limit(options.maxSkills())
            .toList();
        if (skills.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (TenantSkill skill : skills) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("- ").append(skill.name()).append(": ").append(skill.description());
            if (taskContext != null && !taskContext.isBlank() && skill.tags() != null && !skill.tags().isEmpty()) {
                sb.append(" [tags: ").append(String.join(", ", skill.tags())).append("]");
            }
        }
        context.addSegment(new PromptContext.Segment(
            "foundation-skills",
            "skills://tenant-available",
            "Available Tenant Skills",
            truncate(sb.toString(), options.maxCharsPerFoundationSegment()),
            Map.of("tenantId", tenant.getTenantId(), "count", skills.size())
        ));
    }

    private void includeOrgKnowledge(PromptContext context, TenantContext tenant, String taskContext, ResolveOptions options) {
        if (!options.includeOrgKnowledge() || taskContext == null || taskContext.isBlank()) return;
        OrganizationalKnowledgeBase kb = tenant.getOrgKnowledgeBase();
        String rag = kb.buildRagContext(taskContext, options.maxKnowledgeEntries(), options.maxCharsPerKnowledgeEntry());
        if (rag != null && !rag.isBlank() && !"# Organizational Knowledge\n\n".equals(rag)) {
            context.addSegment(new PromptContext.Segment(
                "foundation-org-knowledge",
                "knowledge://rag-search",
                "Organizational Knowledge Context",
                truncate(rag, options.maxCharsPerFoundationSegment()),
                Map.of("tenantId", tenant.getTenantId(), "query", taskContext)
            ));
        }
    }

    public static PromptRef parse(String ref) {
        if (ref == null || !ref.startsWith("prompt://")) return null;
        String value = ref.substring("prompt://".length()).trim();
        if (value.isBlank() || value.contains("/")) return null;
        String assetId = value;
        Integer version = null;
        int sep = value.indexOf("#v");
        if (sep >= 0) {
            assetId = value.substring(0, sep);
            String versionText = value.substring(sep + 2);
            if (assetId.isBlank() || versionText.isBlank() || versionText.contains("#")) return null;
            try { version = Integer.parseInt(versionText); }
            catch (NumberFormatException e) { return null; }
            if (version <= 0) return null;
        } else if (value.contains("#")) {
            return null;
        }
        return new PromptRef(assetId, version);
    }

    private static String canonicalRef(String assetId, int version) {
        return "prompt://" + assetId + "#v" + version;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) return "";
        if (maxChars <= 0 || value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "...";
    }

    public record PromptRef(String assetId, Integer version) {}

    public record ResolveOptions(
        boolean includeMemory,
        boolean includeSkills,
        boolean includeOrgKnowledge,
        int maxSkills,
        int maxKnowledgeEntries,
        int maxCharsPerKnowledgeEntry,
        int maxCharsPerFoundationSegment
    ) {
        public static ResolveOptions promptOnly() {
            return new ResolveOptions(false, false, false, 0, 0, 0, 2000);
        }

        public static ResolveOptions withFoundationContext() {
            return new ResolveOptions(true, true, true, 5, 3, 800, 2000);
        }

        public boolean includeFoundationContext() {
            return includeMemory || includeSkills || includeOrgKnowledge;
        }
    }
}
