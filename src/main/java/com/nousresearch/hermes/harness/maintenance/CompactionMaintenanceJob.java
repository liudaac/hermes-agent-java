package com.nousresearch.hermes.harness.maintenance;

import com.nousresearch.hermes.harness.AgentContext;
import com.nousresearch.hermes.harness.compaction.CompactionTrigger;

/**
 * Checks if context needs compaction during idle time.
 */
public class CompactionMaintenanceJob implements MaintenanceJob {
    private final AgentContext ctx;

    public CompactionMaintenanceJob(AgentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() { return "compaction-check"; }

    @Override
    public int priority() { return 10; }

    @Override
    public void run() {
        try {
            var engine = new com.nousresearch.hermes.harness.compaction.BasicCompactionEngine();
            var result = engine.compactIfNeeded(
                ctx.history(),
                CompactionTrigger.PRESSURE,
                ctx.modelClient()
            );
            if (result.success()) {
                ctx.sessionLog().appendTrace(
                    com.nousresearch.hermes.harness.session.SessionEventType.CUSTOM,
                    java.util.Map.of("maintenance", "compaction", "messagesCompacted", result.messagesCompacted())
                );
            }
        } catch (Exception e) {
            // Silent failure - maintenance jobs shouldn't crash
        }
    }
}
