package com.nousresearch.hermes.canary;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Canary release service for gradual rollout of blueprint versions.
 *
 * <p>Traffic split logic:</p>
 * <ul>
 *   <li>If no active canary: 100% to active version</li>
 *   <li>If active canary with N%: N% to toVersion, (100-N)% to fromVersion</li>
 * </ul>
 */
public class CanaryReleaseService {
    private final FileCanaryReleaseRepository repository;
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;

    public CanaryReleaseService(WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileCanaryReleaseRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, teamBlueprintService);
    }

    public CanaryReleaseService(Path workspacesRoot, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileCanaryReleaseRepository(workspacesRoot), workspaceService, teamBlueprintService);
    }

    public CanaryReleaseService(FileCanaryReleaseRepository repository, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.teamBlueprintService = teamBlueprintService;
    }

    /** Start a new canary release from current active version to target version. */
    public CanaryReleaseRecord startCanary(String workspaceId, String teamId, int toVersion, int initialTrafficPercent, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        var team = teamBlueprintService.requireTeamBlueprint(workspaceId, teamId);
        int fromVersion = team.getActiveVersion();
        if (toVersion == fromVersion) {
            throw new IllegalArgumentException("Target version " + toVersion + " is already active");
        }
        // Check no active canary already
        repository.findActive(workspaceId, teamId).ifPresent(r -> {
            throw new IllegalStateException("Active canary already exists: " + r.getReleaseId());
        });
        Instant now = Instant.now();
        CanaryReleaseRecord record = new CanaryReleaseRecord()
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
            .setReleaseId("canary-" + UUID.randomUUID().toString().substring(0, 8))
            .setFromVersion(fromVersion)
            .setToVersion(toVersion)
            .setTrafficPercent(clamp(initialTrafficPercent, 0, 100))
            .setStatus(CanaryReleaseRecord.ACTIVE)
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    /** Update traffic percentage for an active canary. */
    public CanaryReleaseRecord updateTraffic(String workspaceId, String teamId, String releaseId, int trafficPercent) {
        CanaryReleaseRecord record = requireActive(workspaceId, teamId, releaseId);
        record.setTrafficPercent(clamp(trafficPercent, 0, 100));
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return record;
    }

    /** Promote canary to 100% and complete it. */
    public CanaryReleaseRecord promote(String workspaceId, String teamId, String releaseId) {
        CanaryReleaseRecord record = requireActive(workspaceId, teamId, releaseId);
        teamBlueprintService.activateVersion(workspaceId, teamId, record.getToVersion());
        record.setTrafficPercent(100);
        record.setStatus(CanaryReleaseRecord.COMPLETED);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return record;
    }

    /** Rollback canary and restore fromVersion as active. */
    public CanaryReleaseRecord rollback(String workspaceId, String teamId, String releaseId) {
        CanaryReleaseRecord record = requireActive(workspaceId, teamId, releaseId);
        teamBlueprintService.activateVersion(workspaceId, teamId, record.getFromVersion());
        record.setStatus(CanaryReleaseRecord.ROLLED_BACK);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return record;
    }

    /** Get active canary for a team, if any. */
    public Optional<CanaryReleaseRecord> getActiveCanary(String workspaceId, String teamId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.findActive(workspaceId, teamId);
    }

    /** List all canary releases for a team. */
    public List<CanaryReleaseRecord> listCanaries(String workspaceId, String teamId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.listByTeam(workspaceId, teamId);
    }

    /**
     * Record a run outcome against the active canary's metrics.
     * Tracks per-version success/failure counts so users can compare canary vs baseline.
     */
    public void recordRunOutcome(String workspaceId, String teamId, int teamVersion,
                                  String runStatus, long durationMs, double cost) {
        Optional<CanaryReleaseRecord> opt = repository.findActive(workspaceId, teamId);
        if (opt.isEmpty()) return;

        CanaryReleaseRecord canary = opt.get();
        Map<String, Object> metrics = canary.getMetrics() != null ? canary.getMetrics() : new java.util.LinkedHashMap<>();

        boolean isCanaryVersion = teamVersion == canary.getToVersion();
        String prefix = isCanaryVersion ? "canary" : "baseline";

        long total = ((Number) metrics.getOrDefault(prefix + "Total", 0L)).longValue() + 1;
        long succeeded = ((Number) metrics.getOrDefault(prefix + "Succeeded", 0L)).longValue();
        long failed = ((Number) metrics.getOrDefault(prefix + "Failed", 0L)).longValue();
        double totalDuration = ((Number) metrics.getOrDefault(prefix + "TotalDurationMs", 0.0)).doubleValue() + durationMs;
        double totalCost = ((Number) metrics.getOrDefault(prefix + "TotalCost", 0.0)).doubleValue() + cost;

        if ("COMPLETED".equals(runStatus)) succeeded++;
        else if ("FAILED".equals(runStatus)) failed++;

        metrics.put(prefix + "Total", total);
        metrics.put(prefix + "Succeeded", succeeded);
        metrics.put(prefix + "Failed", failed);
        metrics.put(prefix + "TotalDurationMs", totalDuration);
        metrics.put(prefix + "TotalCost", totalCost);
        metrics.put(prefix + "AvgDurationMs", total > 0 ? totalDuration / total : 0.0);
        metrics.put(prefix + "AvgCost", total > 0 ? totalCost / total : 0.0);
        metrics.put(prefix + "SuccessRate", total > 0 ? (double) succeeded / total : 0.0);

        canary.setMetrics(metrics);
        canary.setUpdatedAt(Instant.now());
        repository.save(canary);
    }

    /**
     * Determine which version should handle a request.
     * Returns toVersion for canary traffic, otherwise active version.
     */
    public int resolveVersion(String workspaceId, String teamId, String requestKey) {
        Optional<CanaryReleaseRecord> canary = repository.findActive(workspaceId, teamId);
        if (canary.isEmpty()) {
            return teamBlueprintService.requireTeamBlueprint(workspaceId, teamId).getActiveVersion();
        }
        // Deterministic hash-based routing for stable request-to-version mapping
        int hash = Math.abs(requestKey.hashCode()) % 100;
        return hash < canary.get().getTrafficPercent() ? canary.get().getToVersion() : canary.get().getFromVersion();
    }

    private CanaryReleaseRecord requireActive(String workspaceId, String teamId, String releaseId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.find(workspaceId, teamId, releaseId)
            .filter(r -> CanaryReleaseRecord.ACTIVE.equals(r.getStatus()))
            .orElseThrow(() -> new CanaryNotFoundException(workspaceId, teamId, releaseId));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class CanaryNotFoundException extends RuntimeException {
        public CanaryNotFoundException(String workspaceId, String teamId, String releaseId) {
            super("Active canary not found: " + releaseId + " for team " + teamId + " in workspace " + workspaceId);
        }
    }
}
