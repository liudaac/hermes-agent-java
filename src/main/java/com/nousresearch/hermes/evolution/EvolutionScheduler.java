package com.nousresearch.hermes.evolution;

import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic evolution proposal generator.
 *
 * <p>Runs once per day (configurable via system property
 * {@code evolution.scan.interval.hours}, default 24h) and scans recent
 * failed runs across all workspaces. For each workspace + team combo
 * with recurring failure patterns, {@link EvolutionProposalGenerator}
 * creates proposals that surface in the Portal's Insights page for
 * human review.
 *
 * <p>This is F8 from the Sprint 5 roadmap: "Evolution Proposal 定时调度".
 * The generator itself was already implemented; it just needed a trigger.
 */
public class EvolutionScheduler {
    private static final Logger logger = LoggerFactory.getLogger(EvolutionScheduler.class);

    private final ScheduledExecutorService scheduler;
    private final WorkspaceService workspaceService;
    private final BusinessRunService runService;
    private final EvolutionProposalGenerator generator;

    /** Hours between scans. Override with -Devolution.scan.interval.hours=12 */
    private final long intervalHours;

    public EvolutionScheduler(
            WorkspaceService workspaceService,
            BusinessRunService runService,
            EvolutionProposalService proposalService) {
        this.workspaceService = workspaceService;
        this.runService = runService;
        this.generator = new EvolutionProposalGenerator(proposalService, runService);
        this.intervalHours = Long.parseLong(
            System.getProperty("evolution.scan.interval.hours", "24"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evolution-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** Start the periodic scan. Initial delay = interval (don't scan on boot). */
    public void start() {
        logger.info("Evolution scheduler started: scan every {}h", intervalHours);
        scheduler.scheduleAtFixedRate(
            this::scanAllWorkspaces,
            intervalHours,
            intervalHours,
            TimeUnit.HOURS
        );
    }

    /** Stop the scheduler. */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Scan all workspaces for failure patterns and generate proposals.
     * Called by the scheduler; also callable manually (e.g. from a
     * REST endpoint for on-demand analysis).
     *
     * @return total proposals generated across all workspaces
     */
    public int scanAllWorkspaces() {
        List<WorkspaceRecord> workspaces = workspaceService.listWorkspaces();
        int totalProposals = 0;
        for (WorkspaceRecord ws : workspaces) {
            try {
                // Scan last 50 runs for this workspace, looking for failure patterns.
                // Pass null teamId to scan across all teams in the workspace.
                var proposals = generator.generateFromRecentRuns(
                    ws.getWorkspaceId(), null, 50);
                totalProposals += proposals.size();
                if (!proposals.isEmpty()) {
                    logger.info("Workspace {} generated {} evolution proposal(s)",
                        ws.getWorkspaceId(), proposals.size());
                }
            } catch (Exception e) {
                logger.warn("Evolution scan failed for workspace {}: {}",
                    ws.getWorkspaceId(), e.getMessage());
            }
        }
        if (totalProposals > 0) {
            logger.info("Evolution scan complete: {} proposal(s) across {} workspace(s)",
                totalProposals, workspaces.size());
        }
        return totalProposals;
    }
}
