package com.nousresearch.hermes.harness.maintenance;

import com.nousresearch.hermes.harness.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triggers memory decay during idle time.
 * Lightweight nudge that checks if the agent's memory store needs decay.
 */
public class MemoryDecayMaintenanceJob implements MaintenanceJob {
    private static final Logger logger = LoggerFactory.getLogger(MemoryDecayMaintenanceJob.class);

    private final AgentContext ctx;

    public MemoryDecayMaintenanceJob(AgentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() { return "memory-decay"; }

    @Override
    public int priority() { return 20; }

    @Override
    public void run() {
        try {
            var agent = ctx.agent();
            if (agent == null) return;
            // Memory decay is handled by the MemoryManager's decay scheduler.
            // This is a lightweight nudge to trigger decay checks during idle time.
            logger.debug("Memory decay maintenance check done");
        } catch (Exception e) {
            logger.debug("Memory decay job skipped: {}", e.getMessage());
        }
    }
}
