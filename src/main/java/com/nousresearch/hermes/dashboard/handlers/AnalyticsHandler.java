package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.Constants;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for analytics aggregation endpoints backed by the dashboard sessions DB.
 *
 * Reads from the same SQLite database as SessionHandler and returns
 * daily/model/totals aggregates. Skills data is not yet tracked in the session
 * schema so the skills section returns zeroed placeholders.
 */
public class AnalyticsHandler {
    private static final Logger logger = LoggerFactory.getLogger(AnalyticsHandler.class);

    private final Path dbPath;

    public AnalyticsHandler() {
        this(Constants.getHermesHome().resolve("sessions.db"));
    }

    public AnalyticsHandler(Path dbPath) {
        this.dbPath = dbPath.toAbsolutePath().normalize();
    }

    /** GET /api/analytics/usage */
    public void getUsage(Context ctx) {
        try {
            int days = ctx.queryParamAsClass("days", Integer.class).getOrDefault(30);
            long cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);

            JSONObject result = new JSONObject();
            result.put("daily", loadDaily(cutoff));
            result.put("by_model", loadByModel(cutoff));
            result.put("totals", loadTotals(cutoff));
            result.put("skills", emptySkills());

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error loading analytics: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Error loading analytics: " + e.getMessage()));
        }
    }

    private JSONArray loadDaily(long cutoff) throws SQLException {
        JSONArray daily = new JSONArray();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            PreparedStatement stmt = conn.prepareStatement("""
                SELECT
                    strftime('%Y-%m-%d', last_active / 1000, 'unixepoch') as day,
                    SUM(input_tokens)  as input_tokens,
                    SUM(output_tokens) as output_tokens,
                    COUNT(*)           as sessions
                FROM sessions
                WHERE last_active >= ?
                GROUP BY day
                ORDER BY day ASC
            """);
            stmt.setLong(1, cutoff);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JSONObject entry = new JSONObject();
                entry.put("day", rs.getString("day"));
                entry.put("input_tokens", rs.getLong("input_tokens"));
                entry.put("output_tokens", rs.getLong("output_tokens"));
                entry.put("cache_read_tokens", 0);
                entry.put("reasoning_tokens", 0);
                entry.put("estimated_cost", 0);
                entry.put("actual_cost", 0);
                entry.put("sessions", rs.getInt("sessions"));
                daily.add(entry);
            }
        }
        return daily;
    }

    private JSONArray loadByModel(long cutoff) throws SQLException {
        JSONArray models = new JSONArray();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            PreparedStatement stmt = conn.prepareStatement("""
                SELECT
                    COALESCE(NULLIF(model, ''), 'unknown') as model,
                    SUM(input_tokens)  as input_tokens,
                    SUM(output_tokens) as output_tokens,
                    COUNT(*)           as sessions
                FROM sessions
                WHERE last_active >= ?
                GROUP BY model
                ORDER BY sessions DESC
            """);
            stmt.setLong(1, cutoff);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JSONObject entry = new JSONObject();
                entry.put("model", rs.getString("model"));
                entry.put("input_tokens", rs.getLong("input_tokens"));
                entry.put("output_tokens", rs.getLong("output_tokens"));
                entry.put("estimated_cost", 0);
                entry.put("sessions", rs.getInt("sessions"));
                models.add(entry);
            }
        }
        return models;
    }

    private JSONObject loadTotals(long cutoff) throws SQLException {
        JSONObject totals = new JSONObject();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            PreparedStatement stmt = conn.prepareStatement("""
                SELECT
                    COUNT(*)           as total_sessions,
                    SUM(input_tokens)  as total_input,
                    SUM(output_tokens) as total_output
                FROM sessions
                WHERE last_active >= ?
            """);
            stmt.setLong(1, cutoff);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                totals.put("total_input", rs.getLong("total_input"));
                totals.put("total_output", rs.getLong("total_output"));
                totals.put("total_cache_read", 0);
                totals.put("total_reasoning", 0);
                totals.put("total_estimated_cost", 0);
                totals.put("total_actual_cost", 0);
                totals.put("total_sessions", rs.getInt("total_sessions"));
            } else {
                totals.put("total_input", 0);
                totals.put("total_output", 0);
                totals.put("total_cache_read", 0);
                totals.put("total_reasoning", 0);
                totals.put("total_estimated_cost", 0);
                totals.put("total_actual_cost", 0);
                totals.put("total_sessions", 0);
            }
        }
        return totals;
    }

    private JSONObject emptySkills() {
        JSONObject skills = new JSONObject();
        JSONObject summary = new JSONObject();
        summary.put("total_skill_loads", 0);
        summary.put("total_skill_edits", 0);
        summary.put("total_skill_actions", 0);
        summary.put("distinct_skills_used", 0);
        skills.put("summary", summary);
        skills.put("top_skills", new JSONArray());
        return skills;
    }
}
