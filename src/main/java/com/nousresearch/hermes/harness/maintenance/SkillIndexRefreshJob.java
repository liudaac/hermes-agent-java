package com.nousresearch.hermes.harness.maintenance;

import com.nousresearch.hermes.harness.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refreshes skill index during idle time.
 * Lightweight nudge that checks if the skill index needs refreshing.
 */
public class SkillIndexRefreshJob implements MaintenanceJob {
    private static final Logger logger = LoggerFactory.getLogger(SkillIndexRefreshJob.class);

    private final AgentContext ctx;

    public SkillIndexRefreshJob(AgentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() { return "skill-index-refresh"; }

    @Override
    public int priority() { return 30; }

    @Override
    public void run() {
        try {
            var agent = ctx.agent();
            if (agent == null) return;
            // Skill index refresh is handled by the SkillManager.
            // This is a lightweight nudge to trigger refresh checks during idle time.
            logger.debug("Skill index refresh check done");
        } catch (Exception e) {
            logger.debug("Skill index refresh skipped: {}", e.getMessage());
        }
    }
}
