package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Runs dashboard cron jobs by invoking the local {@link AIAgent}.
 *
 * <p>The job's prompt is sent as a one-shot user message. We give every run a
 * stable session id of the form {@code cron-<jobId>-<timestamp>} so the resulting
 * conversation flows into the existing sessions database via the gateway session
 * sync, and Analytics can attribute the run.</p>
 *
 * <p>Currently only the {@code local} deliver target executes the agent. Other
 * delivery targets (feishu/telegram/discord/...) require a gateway adapter and
 * will throw, surfacing as a structured failure in the CronHandler response.</p>
 */
public class AgentCronRunner implements CronJobExecutor.JobRunner {
    private static final Logger logger = LoggerFactory.getLogger(AgentCronRunner.class);

    private final HermesConfig config;
    private final BiFunction<HermesConfig, String, AIAgent> agentFactory;

    public AgentCronRunner(HermesConfig config) {
        this(config, AIAgent::new);
    }

    AgentCronRunner(HermesConfig config, BiFunction<HermesConfig, String, AIAgent> agentFactory) {
        this.config = Objects.requireNonNull(config, "config");
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
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
        logger.info("Running dashboard cron job {} via AIAgent session {}", job.id, sessionId);

        AIAgent agent = agentFactory.apply(config, sessionId);
        String response = agent.processMessage(job.prompt);
        return response != null ? response : "";
    }

    private static String safeJobId(String id) {
        if (id == null || id.isBlank()) {
            return "unknown";
        }
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
