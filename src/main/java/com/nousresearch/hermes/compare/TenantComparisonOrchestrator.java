package com.nousresearch.hermes.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantAIAgent;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-side orchestrator for multi-tenant comparison runs.
 */
public class TenantComparisonOrchestrator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TenantComparisonOrchestrator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final TenantManager tenantManager;
    private final HermesConfig config;
    private final Path runsDir;
    private final ConcurrentHashMap<String, TenantComparisonRun> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "compare-runner");
        t.setDaemon(true);
        return t;
    });

    public TenantComparisonOrchestrator(TenantManager tenantManager, HermesConfig config) {
        this.tenantManager = tenantManager;
        this.config = config;
        this.runsDir = Constants.getHermesHome().resolve("compare").resolve("runs");
        try {
            Files.createDirectories(runsDir);
            loadPersistedRuns();
        } catch (IOException e) {
            logger.error("Failed to initialize comparison persistence", e);
        }
    }

    public TenantComparisonRun createRun(String topic, int rounds, List<String> tenantIds) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        if (tenantIds == null || tenantIds.size() < 2) {
            throw new IllegalArgumentException("at least two tenant_ids are required");
        }

        List<String> cleanedTenantIds = tenantIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (cleanedTenantIds.size() < 2) {
            throw new IllegalArgumentException("at least two distinct tenant_ids are required");
        }

        TenantComparisonRun run = new TenantComparisonRun(topic.trim(), rounds, cleanedTenantIds);
        runs.put(run.getId(), run);
        persistRun(run);
        executor.submit(() -> execute(run));
        return run;
    }

    public TenantComparisonRun getRun(String runId) {
        return runs.get(runId);
    }

    public List<Map<String, Object>> listRuns() {
        return runs.values().stream()
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .map(TenantComparisonRun::toSummaryMap)
            .toList();
    }

    public boolean stopRun(String runId) {
        TenantComparisonRun run = runs.get(runId);
        if (run == null) {
            return false;
        }
        run.requestStop();
        persistRun(run);
        return true;
    }

    private void execute(TenantComparisonRun run) {
        run.markRunning();
        persistRun(run);
        try {
            String currentMessage = run.getTopic();
            List<TenantComparisonRun.Participant> participants = run.getParticipants();
            int totalTurns = run.getRounds() * participants.size();

            for (int turn = 0; turn < totalTurns; turn++) {
                if (run.isStopRequested()) {
                    run.markStopped();
                    persistRun(run);
                    return;
                }

                TenantComparisonRun.Participant participant = participants.get(turn % participants.size());
                String tenantId = participant.getTenantId();
                recordEvent(run, tenantId, "user", currentMessage);

                TenantContext context = tenantManager.getOrCreateTenant(
                    tenantId,
                    TenantProvisioningRequest.builder(tenantId, "compare-orchestrator")
                        .tenantName(tenantId)
                        .description("Auto-created for comparison run")
                        .build()
                );
                TenantAIAgent agent = context.getOrCreateAgent(participant.getSessionId(), config);
                String response = agent.processMessage(currentMessage);
                recordEvent(run, tenantId, "assistant", response);
                currentMessage = response;
            }

            if (run.isStopRequested()) {
                run.markStopped();
                persistRun(run);
                return;
            }

            String conclusion = generateConclusion(run);
            run.markCompleted(conclusion);
            persistRun(run);
        } catch (Exception e) {
            logger.error("Comparison run failed: {}", run.getId(), e);
            run.markFailed(e.getMessage());
            persistRun(run);
        }
    }

    private String generateConclusion(TenantComparisonRun run) {
        try {
            TenantContext context = tenantManager.getOrCreateTenant(
                "default",
                TenantProvisioningRequest.builder("default", "compare-orchestrator")
                    .tenantName("default")
                    .description("Default tenant for comparison conclusions")
                    .build()
            );
            TenantAIAgent judge = context.getOrCreateAgent("compare-judge-" + run.getId(), config);
            return judge.processMessage(buildConclusionPrompt(run));
        } catch (Exception e) {
            logger.warn("Conclusion generation failed for run {}: {}", run.getId(), e.getMessage());
            return "Conclusion generation failed: " + e.getMessage();
        }
    }

    private String buildConclusionPrompt(TenantComparisonRun run) {
        Map<String, List<TenantComparisonRun.Event>> byTenant = new LinkedHashMap<>();
        for (TenantComparisonRun.Event event : run.getEvents()) {
            byTenant.computeIfAbsent(event.tenantId(), k -> new ArrayList<>()).add(event);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You are a neutral evaluator. Compare all tenant conversations below.\n");
        sb.append("Return: consensus, disagreements, each participant's strengths/weaknesses, final recommendation, and next actions.\n\n");
        for (Map.Entry<String, List<TenantComparisonRun.Event>> entry : byTenant.entrySet()) {
            sb.append("# Tenant: ").append(entry.getKey()).append("\n");
            for (TenantComparisonRun.Event event : entry.getValue()) {
                sb.append(event.role().toUpperCase()).append(": ").append(event.content()).append("\n\n");
            }
            sb.append("---\n\n");
        }
        return sb.toString();
    }

    private void recordEvent(TenantComparisonRun run, String tenantId, String role, String content) {
        run.addEvent(tenantId, role, content);
        persistRun(run);
    }

    private void loadPersistedRuns() throws IOException {
        if (!Files.exists(runsDir)) {
            return;
        }
        try (var stream = Files.list(runsDir)) {
            stream
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        TenantComparisonRun run = MAPPER.readValue(path.toFile(), TenantComparisonRun.class);
                        // In-flight runs cannot be resumed safely yet; mark them stopped.
                        if (run.getStatus() == TenantComparisonRun.Status.RUNNING ||
                            run.getStatus() == TenantComparisonRun.Status.PENDING) {
                            run.markStopped();
                            persistRun(run);
                        }
                        runs.put(run.getId(), run);
                    } catch (Exception e) {
                        logger.warn("Failed to load comparison run {}: {}", path, e.getMessage());
                    }
                });
        }
        logger.info("Loaded {} persisted comparison runs", runs.size());
    }

    private void persistRun(TenantComparisonRun run) {
        try {
            Files.createDirectories(runsDir);
            Path target = runsDir.resolve(run.getId() + ".json");
            Path tmp = runsDir.resolve(run.getId() + ".json.tmp");
            MAPPER.writeValue(tmp.toFile(), run);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warn("Failed to persist comparison run {}: {}", run.getId(), e.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
