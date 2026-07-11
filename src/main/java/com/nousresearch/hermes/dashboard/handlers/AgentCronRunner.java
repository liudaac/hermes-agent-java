package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantAIAgent;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Runs dashboard cron jobs by invoking a {@link TenantAwareAIAgent} inside a
 * specific workspace/tenant context.
 *
 * <p>If the cron job declares a {@code workspaceId}, we resolve the corresponding
 * {@link TenantContext} via {@link WorkspaceService} and reuse that tenant's
 * agent pool — which is the same pool the Portal/Jarvis chat endpoint uses.
 * This means cron jobs see the same teams, scenarios, tools, skills, memory,
 * and approval policy as a human operating the UI. Previously this runner
 * bootstrapped its own {@code new TenantAwareAIAgent(config, sessionId)} which
 * created a new default tenant, bypassing workspace configuration entirely.
 *
 * <p>When no {@code workspaceId} is set (legacy/default jobs, or jobs that
 * genuinely want a fresh default workspace), we fall back to creating an
 * ephemeral default-tenant agent for backward compatibility.</p>
 */
public class AgentCronRunner implements CronJobExecutor.JobRunner {
    private static final Logger logger = LoggerFactory.getLogger(AgentCronRunner.class);

    private final HermesConfig config;
    private final TenantManager tenantManager;
    private final WorkspaceService workspaceService;

    public AgentCronRunner(HermesConfig config, TenantManager tenantManager, WorkspaceService workspaceService) {
        this.config = Objects.requireNonNull(config, "config");
        this.tenantManager = Objects.requireNonNull(tenantManager, "tenantManager");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
    }

    @Override
    public String run(CronHandler.CronJob job) throws Exception {
        String deliver = job.deliver != null ? job.deliver.toLowerCase() : "local";
        if (!"local".equals(deliver)) {
            throw new UnsupportedOperationException(
                "Cron deliver target '" + deliver + "' is not wired in the dashboard yet; only 'local' runs locally."
            );
        }

        if (job.prompt == null || job.prompt.isBlank()) {
            throw new IllegalArgumentException("Cron job " + job.id + " has empty prompt");
        }

        String sessionId = "cron-" + safeJobId(job.id) + "-" + System.currentTimeMillis();
        logger.info("Running dashboard cron job {} via session {}", job.id, sessionId);

        com.nousresearch.hermes.tenant.core.TenantAIAgent agent;
        String workspaceId = job.workspaceId;
        if (workspaceId != null && !workspaceId.isBlank()) {
            TenantContext tenant = workspaceService.resolveTenantContext(workspaceId);
            if (tenant == null) {
                throw new IllegalStateException("Workspace '" + workspaceId + "' not found for cron job " + job.id);
            }
            agent = tenant.getOrCreateAgent(sessionId, config);
            logger.debug("Cron job {} bound to workspace {} (tenant {})", job.id, workspaceId, tenant.getTenantId());
        } else {
            // Backward compat: legacy jobs without workspaceId run on the default tenant.
            // Wrap TenantAwareAIAgent in the TenantAIAgent wrapper for uniform typing.
            logger.debug("Cron job {} has no workspaceId; running on default tenant", job.id);
            var ctx = tenantManager.getOrCreateTenant("default", null);
            agent = ctx.getOrCreateAgent(sessionId, config);
        }

        String response = agent.processMessage(job.prompt);
        return response != null ? response : "";
    }

    private static String safeJobId(String id) {
        if (id == null || id.isBlank()) return "unknown";
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
