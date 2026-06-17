package com.nousresearch.hermes.prompt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolved prompt/context projection assembled from business prompt refs and foundation context. */
public class PromptContext {
    private String workspaceId;
    private Instant resolvedAt = Instant.now();
    private final List<Segment> segments = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    public String getWorkspaceId() { return workspaceId; }
    public PromptContext setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public Instant getResolvedAt() { return resolvedAt; }
    public List<Segment> getSegments() { return List.copyOf(segments); }
    public List<String> getWarnings() { return List.copyOf(warnings); }
    public Map<String, Object> getMetadata() { return Map.copyOf(metadata); }

    public PromptContext addSegment(Segment segment) { if (segment != null) segments.add(segment); return this; }
    public PromptContext warning(String warning) { if (warning != null && !warning.isBlank()) warnings.add(warning); return this; }
    public PromptContext meta(String key, Object value) { if (key != null) metadata.put(key, value); return this; }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.content() == null || segment.content().isBlank()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("## ").append(segment.title()).append("\n");
            sb.append(segment.content());
        }
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workspaceId", workspaceId);
        map.put("resolvedAt", resolvedAt.toString());
        map.put("segments", segments.stream().map(Segment::toMap).toList());
        map.put("warnings", List.copyOf(warnings));
        map.put("metadata", Map.copyOf(metadata));
        return map;
    }

    public record Segment(String source, String ref, String title, String content, Map<String, Object> metadata) {
        public Segment {
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("source", source);
            map.put("ref", ref);
            map.put("title", title);
            map.put("content", content);
            map.put("metadata", metadata);
            return map;
        }
    }
}
