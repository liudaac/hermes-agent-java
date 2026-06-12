package com.nousresearch.hermes.browser.contract;

import com.nousresearch.hermes.browser.BrowserBridgeConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Probes candidate BrowserBridge daemon path layouts and recommends a config. */
public class BrowserBridgeProviderProbe {
    public record Candidate(String label, String provider, String actionPath, String healthPath, String capabilitiesPath) {}

    public record ProbeResult(
        boolean ok,
        String endpoint,
        Candidate bestCandidate,
        int score,
        BrowserBridgeContractReport bestReport,
        List<Map<String, Object>> candidates
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", ok);
            map.put("endpoint", endpoint);
            map.put("score", score);
            if (bestCandidate != null) {
                map.put("recommended_config", Map.of(
                    "provider", bestCandidate.provider(),
                    "action_path", bestCandidate.actionPath(),
                    "health_path", bestCandidate.healthPath(),
                    "capabilities_path", bestCandidate.capabilitiesPath()
                ));
                map.put("candidate", bestCandidate.label());
            }
            if (bestReport != null) map.put("best_report", bestReport.toMap());
            map.put("candidates", candidates);
            return map;
        }
    }

    private final String endpoint;
    private final int timeoutMs;
    private final List<Candidate> candidates;

    public BrowserBridgeProviderProbe(String endpoint, int timeoutMs) {
        this(endpoint, timeoutMs, defaultCandidates());
    }

    public BrowserBridgeProviderProbe(String endpoint, int timeoutMs, List<Candidate> candidates) {
        this.endpoint = endpoint;
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.candidates = candidates != null && !candidates.isEmpty() ? candidates : defaultCandidates();
    }

    public ProbeResult probe() {
        List<Scored> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            BrowserBridgeConfig config = new BrowserBridgeConfig(
                candidate.provider(), endpoint, timeoutMs,
                candidate.actionPath(), candidate.healthPath(), candidate.capabilitiesPath()
            );
            BrowserBridgeContractReport report = BrowserBridgeContractVerifier.verify(config);
            scored.add(new Scored(candidate, report, score(report)));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        Scored best = scored.isEmpty() ? null : scored.get(0);
        List<Map<String, Object>> rows = scored.stream().map(Scored::toMap).toList();
        return new ProbeResult(
            best != null && best.report().ok(),
            endpoint,
            best != null ? best.candidate() : null,
            best != null ? best.score() : 0,
            best != null ? best.report() : null,
            rows
        );
    }

    private static int score(BrowserBridgeContractReport report) {
        int score = 0;
        for (var check : report.checks()) {
            if (!check.ok()) continue;
            score += switch (check.name()) {
                case "capabilities" -> 30;
                case "health" -> 20;
                case "actions.open" -> 25;
                case "actions.observe" -> 15;
                case "errors.session_missing" -> 10;
                default -> 1;
            };
        }
        return score;
    }

    public static List<Candidate> defaultCandidates() {
        return List.of(
            new Candidate("webbridge-contract-standard", "webbridge-contract", "/actions", "/health", "/capabilities"),
            new Candidate("webbridge-contract-v1", "webbridge-contract", "/v1/actions", "/v1/health", "/v1/capabilities"),
            new Candidate("webbridge-contract-v1-singular-action", "webbridge-contract", "/v1/action", "/v1/health", "/v1/capabilities"),
            new Candidate("webbridge-contract-api", "webbridge-contract", "/api/webbridge/actions", "/api/webbridge/health", "/api/webbridge/capabilities"),
            new Candidate("hermes-standard", "kimi", "/actions", "/health", "/capabilities"),
            new Candidate("hermes-standard-openclaw", "openclaw", "/actions", "/health", "/capabilities"),
            new Candidate("versioned-v1", "kimi", "/v1/actions", "/v1/health", "/v1/capabilities"),
            new Candidate("versioned-v1-singular-action", "kimi", "/v1/action", "/v1/health", "/v1/capabilities"),
            new Candidate("api-browser", "openclaw", "/api/browser/actions", "/api/browser/health", "/api/browser/capabilities"),
            new Candidate("api-bridge", "kimi", "/api/bridge/actions", "/api/bridge/health", "/api/bridge/capabilities")
        );
    }

    private record Scored(Candidate candidate, BrowserBridgeContractReport report, int score) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("label", candidate.label());
            map.put("provider", candidate.provider());
            map.put("score", score);
            map.put("ok", report.ok());
            map.put("action_path", candidate.actionPath());
            map.put("health_path", candidate.healthPath());
            map.put("capabilities_path", candidate.capabilitiesPath());
            map.put("failed_checks", report.checks().stream().filter(c -> !c.ok()).map(BrowserBridgeContractReport.Check::name).toList());
            return map;
        }
    }
}
