package com.nousresearch.hermes.org.multimodal;

import java.time.Instant;
import java.util.*;

/**
 * Multi-modal agent session — coordinates voice, video, image,
 * and text interactions within a single agent conversation.
 *
 * <p>Manages:</p>
 * <ul>
 *   <li>Modal state — which modalities are active</li>
 *   <li>Transcript sync — aligned text transcript of voice</li>
 *   <li>Content moderation — safety checks for rich media</li>
 *   <li>Session orchestration — handoff between modalities</li>
 * </ul>
 */
public class MultiModalSession {

    public enum Modality { TEXT, VOICE, IMAGE, VIDEO, SCREEN_SHARE }
    public enum ContentClass { SAFE, NEEDS_REVIEW, BLOCKED }

    /** Session identifier. */
    private final String sessionId;

    /** Which modalities are active. */
    private final Set<Modality> activeModalities = EnumSet.noneOf(Modality.class);

    /** Synchronized transcript of all interactions. */
    private final List<TranscriptEntry> transcript = new ArrayList<>();

    /** Pending media items awaiting moderation. */
    private final List<MediaContent> pendingReview = new ArrayList<>();

    /** Approved media archives. */
    private final List<MediaContent> archive = new ArrayList<>();

    /** Current state for each modality. */
    private final Map<Modality, ModalityState> states = new LinkedHashMap<>();

    /** Moderation log. */
    private final List<ModerationEvent> moderationLog = new ArrayList<>();

    /** Session start time. */
    private final Instant startedAt;

    /** Session metadata. */
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    public MultiModalSession(String sessionId, Modality... initialModalities) {
        this.sessionId = sessionId;
        this.startedAt = Instant.now();
        for (Modality m : initialModalities) {
            activeModalities.add(m);
            states.put(m, new ModalityState(m));
        }
    }

    // ---- modality management ----

    /** Activate a new modality (e.g., start voice after text). */
    public void activate(Modality modality) {
        activeModalities.add(modality);
        states.putIfAbsent(modality, new ModalityState(modality));
        states.get(modality).activate();
    }

    /** Deactivate a modality. */
    public void deactivate(Modality modality) {
        activeModalities.remove(modality);
        if (states.containsKey(modality)) states.get(modality).deactivate();
    }

    /** Check if a modality is active. */
    public boolean isActive(Modality m) { return activeModalities.contains(m); }

    /** Get current modalities. */
    public Set<Modality> getActiveModalities() { return EnumSet.copyOf(activeModalities); }

    // ---- transcript ----

    /** Record a transcript entry (aligned across modalities). */
    public void record(String source, String role, Modality modality, String content, Map<String, Object> mediaRefs) {
        TranscriptEntry entry = new TranscriptEntry(
            transcript.size() + 1, source, role, modality, content,
            mediaRefs != null ? mediaRefs : Map.of(), Instant.now());
        transcript.add(entry);
        if (states.containsKey(modality)) states.get(modality).recordActivity();
    }

    /** Get transcript filtered by modality. */
    public List<TranscriptEntry> getTranscript(Modality filter) {
        return transcript.stream()
            .filter(e -> filter == null || e.modality == filter).toList();
    }

    /** Get full transcript. */
    public List<TranscriptEntry> getTranscript() { return List.copyOf(transcript); }

    // ---- content moderation ----

    /** Submit media for moderation before allowing agent to process. */
    public ModerationResult moderate(MediaContent media) {
        MediaClassifier classifier = new MediaClassifier();
        ContentClass classification = classifier.classify(media);

        ModerationEvent event = new ModerationEvent(media.id(), classification, "auto", Instant.now());
        moderationLog.add(event);

        switch (classification) {
            case SAFE -> {
                archive.add(media);
                return new ModerationResult(media.id(), true, "Approved");
            }
            case NEEDS_REVIEW -> {
                pendingReview.add(media);
                return new ModerationResult(media.id(), false, "Pending human review");
            }
            case BLOCKED -> {
                return new ModerationResult(media.id(), false, "Blocked by classification");
            }
        }
        return new ModerationResult(media.id(), false, "Unknown");
    }

    /** Approve a pending review item. */
    public void approve(String mediaId) {
        pendingReview.removeIf(m -> {
            if (m.id().equals(mediaId)) { archive.add(m); return true; }
            return false;
        });
    }

    /** Reject a pending review item. */
    public void reject(String mediaId, String reason) {
        pendingReview.removeIf(m -> m.id().equals(mediaId));
        moderationLog.add(new ModerationEvent(mediaId, ContentClass.BLOCKED, reason, Instant.now()));
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSessionSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("session_id", sessionId);
        s.put("active_modalities", activeModalities.stream().map(Enum::name).toList());
        s.put("transcript_entries", transcript.size());
        s.put("archive_size", archive.size());
        s.put("pending_review", pendingReview.size());
        s.put("moderation_events", moderationLog.size());
        s.put("started", startedAt.toString());
        return s;
    }

    public String getSessionId() { return sessionId; }
    public Instant getStartedAt() { return startedAt; }
    public List<MediaContent> getArchive() { return List.copyOf(archive); }
    public List<MediaContent> getPendingReview() { return List.copyOf(pendingReview); }

    // ---- inner types ----

    public record TranscriptEntry(int sequence, String source, String role,
                                   Modality modality, String content,
                                   Map<String, Object> mediaRefs, Instant timestamp) {}

    public record MediaContent(String id, String type, String url, long sizeBytes, String description) {}

    public record ModerationEvent(String mediaId, ContentClass result, String reviewer, Instant timestamp) {}

    public record ModerationResult(String mediaId, boolean approved, String detail) {}

    /** Per-modality state tracking. */
    static class ModalityState {
        final Modality modality;
        Instant activatedAt;
        Instant lastActivity;
        long eventCount;
        long totalDurationMs;
        boolean active;

        ModalityState(Modality m) { this.modality = m; }
        void activate() { this.activatedAt = Instant.now(); this.active = true; }
        void deactivate() { this.active = false; if (activatedAt != null) totalDurationMs += Instant.now().toEpochMilli() - activatedAt.toEpochMilli(); }
        void recordActivity() { eventCount++; lastActivity = Instant.now(); }
    }

    /** Simple content classifier — production would use a dedicated moderation API. */
    static class MediaClassifier {
        ContentClass classify(MediaContent media) {
            // Heuristic: block executable/script types, flag unknown for review
            String type = media.type().toLowerCase();
            if (type.contains("executable") || type.contains("application/x-sh")
                || type.contains("application/x-msdownload")) {
                return ContentClass.BLOCKED;
            }
            if (type.contains("unknown") || type.contains("octet-stream")) {
                return ContentClass.NEEDS_REVIEW;
            }
            return ContentClass.SAFE;
        }
    }
}