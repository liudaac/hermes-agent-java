package com.nousresearch.hermes.org.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Organization-wide cost attribution and budget control.
 *
 * <p>Tracks token usage and cost at every level:
 * <ul>
 *   <li>Organization → Department → Team → Agent</li>
 *   <li>Per-model cost awareness with configurable pricing</li>
 *   <li>Daily/monthly budgets with alert thresholds</li>
 *   <li>Cost forecasting and optimization recommendations</li>
 * </ul>
 */
public class CostAttribution {

    // ---- pricing ----

    /** Per-model pricing (per 1K tokens, input + output). */
    public record ModelPrice(double inputPer1k, double outputPer1k, String provider) {}

    private static final Map<String, ModelPrice> DEFAULT_PRICES = Map.of(
        "gpt-4", new ModelPrice(0.03, 0.06, "openai"),
        "gpt-4-turbo", new ModelPrice(0.01, 0.03, "openai"),
        "gpt-3.5-turbo", new ModelPrice(0.0005, 0.0015, "openai"),
        "claude-3-opus", new ModelPrice(0.015, 0.075, "anthropic"),
        "claude-3-sonnet", new ModelPrice(0.003, 0.015, "anthropic"),
        "claude-3-haiku", new ModelPrice(0.00025, 0.00125, "anthropic"),
        "deepseek-v3", new ModelPrice(0.001, 0.002, "deepseek"),
        "gemini-pro", new ModelPrice(0.00125, 0.005, "google")
    );

    private final Map<String, ModelPrice> prices = new ConcurrentHashMap<>(DEFAULT_PRICES);

    // ---- hierarchy ----

    /** Agent → Department mapping. */
    private final Map<String, String> agentDepartment = new ConcurrentHashMap<>();

    /** Department → Team mapping. */
    private final Map<String, String> departmentTeam = new ConcurrentHashMap<>();

    // ---- usage tracking ----

    private final Map<String, DailyUsage> dailyUsage = new ConcurrentHashMap<>();
    private final Map<String, MonthlyUsage> monthlyUsage = new ConcurrentHashMap<>();

    // ---- budgets ----

    /** Budgets keyed by scope (agent-id, team, department, or "org"). */
    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();

    /** Custom model price. */
    public void setPrice(String model, double inputPer1k, double outputPer1k, String provider) {
        prices.put(model, new ModelPrice(inputPer1k, outputPer1k, provider));
    }

    /** Register agent hierarchy. */
    public void registerAgent(String agentId, String department, String team) {
        agentDepartment.put(agentId, department);
        if (team != null) departmentTeam.put(department, team);
    }

    /** Set budget for a scope. */
    public void setBudget(String scope, double monthlyBudget, double alertPercent) {
        budgets.put(scope, new Budget(monthlyBudget, alertPercent));
    }

    // ---- recording ----

    /** Record token usage and compute cost. */
    public double recordUsage(String agentId, String model, long inputTokens, long outputTokens) {
        ModelPrice price = prices.getOrDefault(model, new ModelPrice(0.001, 0.002, "unknown"));
        double cost = (inputTokens / 1000.0) * price.inputPer1k
                    + (outputTokens / 1000.0) * price.outputPer1k;

        String day = LocalDate.now().toString();
        String dept = agentDepartment.getOrDefault(agentId, "uncategorized");

        // Daily
        dailyUsage.computeIfAbsent(day, k -> new DailyUsage())
            .record(agentId, dept, model, inputTokens, outputTokens, cost);

        // Monthly
        String month = day.substring(0, 7);
        monthlyUsage.computeIfAbsent(month, k -> new MonthlyUsage())
            .record(agentId, dept, model, inputTokens, outputTokens, cost);

        return cost;
    }

    // ---- queries ----

    /** Today's usage by agent. */
    public Map<String, AgentCost> getDailyByAgent() {
        String day = LocalDate.now().toString();
        DailyUsage du = dailyUsage.get(day);
        return du != null ? du.getByAgent() : Map.of();
    }

    /** Today's usage by department. */
    public Map<String, Double> getDailyByDepartment() {
        String day = LocalDate.now().toString();
        DailyUsage du = dailyUsage.get(day);
        return du != null ? du.getByDepartment() : Map.of();
    }

    /** This month's usage by department. */
    public Map<String, Double> getMonthlyByDepartment() {
        String month = LocalDate.now().toString().substring(0, 7);
        MonthlyUsage mu = monthlyUsage.get(month);
        return mu != null ? mu.getByDepartment() : Map.of();
    }

    /** Total cost today. */
    public double getDailyTotal() {
        String day = LocalDate.now().toString();
        DailyUsage du = dailyUsage.get(day);
        return du != null ? du.getTotalCost() : 0;
    }

    /** Total cost this month. */
    public double getMonthlyTotal() {
        String month = LocalDate.now().toString().substring(0, 7);
        MonthlyUsage mu = monthlyUsage.get(month);
        return mu != null ? mu.getTotalCost() : 0;
    }

    // ---- optimization ----

    /** Suggest cheaper model alternatives based on usage patterns. */
    public List<String> suggestOptimizations() {
        List<String> suggestions = new ArrayList<>();
        String month = LocalDate.now().toString().substring(0, 7);
        MonthlyUsage mu = monthlyUsage.get(month);
        if (mu == null) return suggestions;

        // If using expensive models for simple tasks, suggest cheaper alternatives
        if (mu.getModelTokens("gpt-4") > 1_000_000) {
            suggestions.add("Heavy gpt-4 usage — consider gpt-4-turbo for routine tasks (saves 67% per token)");
        }
        if (mu.getModelTokens("claude-3-opus") > 500_000) {
            suggestions.add("Claude 3 Opus is 5x more expensive than Sonnet — audit whether all tasks need Opus");
        }

        return suggestions;
    }

    /** Forecast monthly cost based on daily burn rate. */
    public double forecastMonthly() {
        int dayOfMonth = LocalDate.now().getDayOfMonth();
        return dayOfMonth > 0 ? (getMonthlyTotal() / dayOfMonth) * 30 : 0;
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("today", String.format("$%.4f", getDailyTotal()));
        s.put("this_month", String.format("$%.4f", getMonthlyTotal()));
        s.put("forecast", String.format("$%.2f", forecastMonthly()));
        s.put("by_agent", getDailyByAgent().entrySet().stream()
            .limit(5).map(e -> e.getKey() + ": $" + String.format("%.4f", e.getValue().cost())).toList());
        s.put("suggestions", suggestOptimizations());
        return s;
    }

    // ---- cost summary for an agent ----

    public record AgentCost(String agentId, long tokens, double cost) {}

    // ---- internal -------

    static class DailyUsage {
        private final Map<String, AgentCost> byAgent = new ConcurrentHashMap<>();
        private final Map<String, Double> byDept = new ConcurrentHashMap<>();
        private final Map<String, Long> byModel = new ConcurrentHashMap<>();
        private final AtomicLong totalCostCents = new AtomicLong();

        void record(String agent, String dept, String model, long in, long out, double cost) {
            byAgent.merge(agent, new AgentCost(agent, in + out, cost),
                (a, b) -> new AgentCost(agent, a.tokens() + b.tokens(), a.cost() + b.cost()));
            byDept.merge(dept, cost, Double::sum);
            byModel.merge(model, in + out, Long::sum);
            totalCostCents.addAndGet((long)(cost * 10000));
        }

        Map<String, AgentCost> getByAgent() { return Map.copyOf(byAgent); }
        Map<String, Double> getByDepartment() { return Map.copyOf(byDept); }
        double getTotalCost() { return totalCostCents.get() / 10000.0; }
    }

    static class MonthlyUsage {
        private final Map<String, Double> byDept = new ConcurrentHashMap<>();
        private final Map<String, Long> byModel = new ConcurrentHashMap<>();
        private final AtomicLong totalCostCents = new AtomicLong();

        void record(String agent, String dept, String model, long in, long out, double cost) {
            byDept.merge(dept, cost, Double::sum);
            byModel.merge(model, in + out, Long::sum);
            totalCostCents.addAndGet((long)(cost * 10000));
        }

        Map<String, Double> getByDepartment() { return Map.copyOf(byDept); }
        long getModelTokens(String model) { return byModel.getOrDefault(model, 0L); }
        double getTotalCost() { return totalCostCents.get() / 10000.0; }
    }

    record Budget(double monthly, double alertPercent) {}
}