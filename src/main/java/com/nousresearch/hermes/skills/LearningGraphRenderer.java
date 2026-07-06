package com.nousresearch.hermes.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Terminal renderer for the learning timeline.
 *
 * <p>Aligned with the original Python Hermes {@code agent/learning_graph_render.py}.
 * Produces an ASCII timeline bar chart: date rows with proportional skill/memory
 * bars colored by the day's dominant category, plus a cumulative trajectory
 * sparkline underneath.</p>
 *
 * <p>Also produces JSON-serializable frame data for REST API consumers
 * (dashboard, /journey command output).</p>
 */
public class LearningGraphRenderer {
    private static final Logger logger = LoggerFactory.getLogger(LearningGraphRenderer.class);

    // Age gradient constants (ported from time-axis.ts / geometry.ts)
    private static final double LEAD_IN = 0.06;
    private static final double AGE_OLD_INK = 0.42;
    private static final double AGE_MID_INK = 0.74;
    private static final double AGE_NEW_INK = 0.95;
    private static final double AGE_MID = 0.52;

    // Glyphs — with ASCII fallbacks for non-UTF-8 terminals
    private static final String SKILL_GLYPH = isUtf8Terminal() ? "●" : "*";
    private static final String MEMORY_GLYPH = isUtf8Terminal() ? "◆" : "+";
    private static final String BAR_CHAR = isUtf8Terminal() ? "━" : "=";
    private static final String SPARK_CHAR = isUtf8Terminal() ? "·" : ".";
    private static final String STAR_CHAR = isUtf8Terminal() ? "✦" : "*";
    private static final String PEAK_CHAR = isUtf8Terminal() ? "☄" : "^";

    private static boolean isUtf8Terminal() {
        String encoding = System.getenv("LANG");
        if (encoding == null) encoding = System.getenv("LC_ALL");
        if (encoding == null) encoding = System.getProperty("file.encoding");
        return encoding != null && encoding.toLowerCase().contains("utf");
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Render the learning timeline as an ASCII chart for terminal output.
     *
     * @param skillNodes  skill nodes from LearningGraphService
     * @param memoryNodes memory nodes from LearningGraphService
     * @param cols        terminal width (default 80)
     * @return multi-line ASCII chart string
     */
    public static String renderTimeline(List<GraphNodeInfo> skillNodes,
                                         List<GraphNodeInfo> memoryNodes,
                                         int cols) {
        cols = Math.max(44, cols);

        List<ChartBucket> buckets = buildChartBuckets(skillNodes, memoryNodes);
        if (buckets.isEmpty()) {
            return "no learning yet — keep using Hermes and it maps out here";
        }

        int maxTotal = buckets.stream().mapToInt(b -> b.total).max().orElse(1);
        if (maxTotal == 0) maxTotal = 1;
        int labelW = Math.min(12, buckets.stream().mapToInt(b -> b.label.length()).max().orElse(8));
        int barW = Math.max(14, cols - labelW - 16);

        StringBuilder sb = new StringBuilder();
        int visible = 0;

        // Header
        sb.append("\n");
        sb.append(" ").append(skillNodes.size()).append(" skills · ")
          .append(memoryNodes.size()).append(" memories · ")
          .append(buckets.size()).append(" time periods\n");
        sb.append("\n");

        for (ChartBucket bucket : buckets) {
            visible += bucket.total;
            double ink = recencyInk(bucket.recency);
            int barLen = Math.max(1, (int) Math.round((double) bucket.total / maxTotal * barW));
            if (bucket.total == 0) barLen = 0;

            int skillLen = bucket.total > 0
                ? (int) Math.round((double) bucket.skills / bucket.total * barLen) : 0;
            if (bucket.skills > 0 && skillLen == 0) skillLen = 1;
            int memoryLen = barLen - skillLen;

            sb.append(String.format("%" + labelW + "s │ ", bucket.label));

            if (bucket.skills > 0) {
                sb.append(SKILL_GLYPH);
                sb.append(BAR_CHAR.repeat(Math.max(0, skillLen - 1)));
            }
            if (bucket.memories > 0) {
                if (memoryLen == 1) {
                    sb.append(MEMORY_GLYPH);
                } else {
                    sb.append(MEMORY_GLYPH);
                    sb.append(BAR_CHAR.repeat(Math.max(0, memoryLen - 2)));
                    sb.append(MEMORY_GLYPH);
                }
            }
            if (barLen < barW) {
                sb.append(" ".repeat(barW - barLen));
            }

            sb.append("  ");
            sb.append(bucket.skills);
            if (bucket.memories > 0) {
                sb.append("+").append(bucket.memories);
            }

            if (bucket.total == maxTotal && maxTotal > 1) {
                sb.append("  " + PEAK_CHAR + " peak");
            }
            sb.append("\n");
        }

        // Trajectory sparkline
        sb.append(" ".repeat(labelW + 2));
        sb.append(renderTrajectory(buckets, Math.max(12, cols - labelW - 13)));
        sb.append("\n");

        // Legend
        sb.append("\n");
        sb.append("  ").append(SKILL_GLYPH).append(" skills (")
          .append(skillNodes.size()).append(")   ");
        sb.append(MEMORY_GLYPH).append(" memories (")
          .append(memoryNodes.size()).append(")\n");

        return sb.toString();
    }

    /**
     * Build a JSON-serializable frame for REST API consumers.
     */
    public static Map<String, Object> renderFrame(List<GraphNodeInfo> skillNodes,
                                                    List<GraphNodeInfo> memoryNodes,
                                                    int cols, int rows) {
        List<ChartBucket> buckets = buildChartBuckets(skillNodes, memoryNodes);

        Map<String, Object> result = new LinkedHashMap<>();
        if (buckets.isEmpty()) {
            result.put("grid", List.of(List.of("no learning yet")));
            result.put("visible", 0);
            return result;
        }

        int maxTotal = buckets.stream().mapToInt(b -> b.total).max().orElse(1);
        if (maxTotal == 0) maxTotal = 1;

        List<Map<String, Object>> frameRows = new ArrayList<>();
        int visible = 0;
        for (int i = 0; i < buckets.size(); i++) {
            ChartBucket b = buckets.get(i);
            visible += b.total;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("label", b.label);
            row.put("date", b.dateStr);
            row.put("skills", b.skills);
            row.put("memories", b.memories);
            row.put("total", b.total);
            row.put("category", b.dominantCategory);

            // Node details for drill-down
            List<Map<String, Object>> nodeDetails = new ArrayList<>();
            for (GraphNodeInfo node : b.nodes) {
                Map<String, Object> nd = new LinkedHashMap<>();
                nd.put("id", node.id);
                nd.put("glyph", node.isMemory ? MEMORY_GLYPH : SKILL_GLYPH);
                nd.put("label", truncate(node.label, 26));
                nd.put("meta", node.meta);
                nd.put("style", node.isMemory ? "memory" : "skill");
                nodeDetails.add(nd);
            }
            row.put("nodes", nodeDetails);
            frameRows.add(row);
        }

        // Legend
        List<Map<String, Object>> legend = new ArrayList<>();
        legend.add(Map.of("glyph", SKILL_GLYPH, "label", "skills (" + skillNodes.size() + ")"));
        legend.add(Map.of("glyph", MEMORY_GLYPH, "label", "memories (" + memoryNodes.size() + ")"));

        // Summary
        List<String> summary = new ArrayList<>();
        summary.add(skillNodes.size() + " learned skills · " + memoryNodes.size() + " memories");

        result.put("rows", frameRows);
        result.put("legend", legend);
        result.put("summary", summary);
        result.put("visible", visible);
        result.put("count", skillNodes.size() + memoryNodes.size());
        result.put("cols", cols);
        result.put("rows", rows);

        return result;
    }

    // ── Internal: bucketing ───────────────────────────────────────────────

    private static List<ChartBucket> buildChartBuckets(List<GraphNodeInfo> skillNodes,
                                                         List<GraphNodeInfo> memoryNodes) {
        List<GraphNodeInfo> all = new ArrayList<>();
        all.addAll(skillNodes);
        all.addAll(memoryNodes);
        if (all.isEmpty()) return List.of();

        // Group by date (day granularity for recent, month for older)
        Map<LocalDate, ChartBucket> byDate = new TreeMap<>();
        for (GraphNodeInfo node : all) {
            if (node.timestamp == null) continue;
            LocalDate date = node.timestamp.atZone(ZoneOffset.UTC).toLocalDate();
            byDate.computeIfAbsent(date, d -> new ChartBucket(
                d.format(DateTimeFormatter.ofPattern("d MMM")),
                node.timestamp
            )).add(node);
        }

        return new ArrayList<>(byDate.values());
    }

    // ── Internal: trajectory sparkline ────────────────────────────────────

    private static String renderTrajectory(List<ChartBucket> buckets, int width) {
        if (buckets.isEmpty()) return "";
        int total = buckets.stream().mapToInt(b -> b.total).sum();
        if (total == 0) total = 1;

        int acc = 0;
        List<Integer> points = new ArrayList<>();
        for (ChartBucket b : buckets) {
            acc += b.total;
            points.add((int) Math.round((double) acc / total * (width - 1)));
        }

        char[] cells = new char[width];
        Arrays.fill(cells, ' ');
        int last = 0;
        for (int p : points) {
            for (int x = Math.min(last, p); x <= Math.max(last, p); x++) {
                if (x >= 0 && x < width && cells[x] == ' ') cells[x] = SPARK_CHAR.charAt(0);
            }
            if (p >= 0 && p < width) cells[p] = STAR_CHAR.charAt(0);
            last = p;
        }

        return "trajectory " + new String(cells);
    }

    // ── Internal: recency ink ─────────────────────────────────────────────

    private static double recencyInk(double rec) {
        double t = clamp(rec, 0.0, 1.0);
        if (t <= AGE_MID) {
            return AGE_OLD_INK + (AGE_MID_INK - AGE_OLD_INK) * smoothstep(t / AGE_MID);
        }
        return AGE_MID_INK + (AGE_NEW_INK - AGE_MID_INK) * smoothstep((t - AGE_MID) / (1 - AGE_MID));
    }

    private static double smoothstep(double p) {
        p = clamp(p, 0.0, 1.0);
        return p * p * (3 - 2 * p);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "unknown";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ── Data classes ──────────────────────────────────────────────────────

    /**
     * Lightweight node info for rendering.
     */
    public static class GraphNodeInfo {
        public String id;
        public String label;
        public String category;
        public String meta;
        public boolean isMemory;
        public Instant timestamp;
        public int useCount;
        public boolean pinned;
    }

    private static class ChartBucket {
        final String label;
        final Instant timestamp;
        final String dateStr;
        int skills = 0;
        int memories = 0;
        int total = 0;
        String dominantCategory = null;
        double recency = 1.0;
        final List<GraphNodeInfo> nodes = new ArrayList<>();

        ChartBucket(String label, Instant timestamp) {
            this.label = label;
            this.timestamp = timestamp;
            this.dateStr = timestamp.atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("d MMM yyyy"));
        }

        void add(GraphNodeInfo node) {
            nodes.add(node);
            if (node.isMemory) {
                memories++;
            } else {
                skills++;
                // Track dominant category
                if (node.category != null) {
                    // Simple: first category wins, could count properly
                    if (dominantCategory == null) dominantCategory = node.category;
                }
            }
            total++;
        }
    }
}
