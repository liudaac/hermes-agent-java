package com.nousresearch.hermes.harness.maintenance;

import com.nousresearch.hermes.harness.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates a short title for the session based on the first few messages.
 */
public class SessionTitleJob implements MaintenanceJob {
    private static final Logger logger = LoggerFactory.getLogger(SessionTitleJob.class);

    private final AgentContext ctx;

    public SessionTitleJob(AgentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() { return "session-title"; }

    @Override
    public int priority() { return 40; }

    @Override
    public void run() {
        try {
            var history = ctx.history();
            if (history == null || history.size() < 2) return;

            // Find first user message
            String firstUser = history.stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(com.nousresearch.hermes.model.ModelMessage::getContent)
                .findFirst()
                .orElse(null);

            if (firstUser == null || firstUser.isBlank()) return;

            // Simple title: first 60 chars of first user message
            String title = firstUser.length() > 60
                ? firstUser.substring(0, 57) + "..."
                : firstUser;

            ctx.sessionLog().appendTrace(
                com.nousresearch.hermes.harness.session.SessionEventType.CUSTOM,
                java.util.Map.of("maintenance", "session-title", "title", title)
            );
            logger.debug("Session title: {}", title);
        } catch (Exception e) {
            logger.debug("Session title job skipped: {}", e.getMessage());
        }
    }
}
