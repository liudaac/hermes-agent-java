package com.nousresearch.hermes.harness.session.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nousresearch.hermes.improvement.SignalCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * File-based SessionLibrary implementation for local / single-node mode.
 *
 * <p>Stores each session asset as a JSON file under
 * {@code <hermes.home>/sessions/<tenantId>/<sessionId>.json}.</p>
 */
public class LocalSessionLibrary implements SessionLibrary {

    private static final Logger logger = LoggerFactory.getLogger(LocalSessionLibrary.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path sessionsRoot;
    private final Map<String, SessionAsset> cache = new ConcurrentHashMap<>();
    private SignalCollector signalCollector;

    public LocalSessionLibrary(Path hermesHome) {
        this.sessionsRoot = hermesHome.resolve("session-assets");
        try {
            Files.createDirectories(sessionsRoot);
        } catch (IOException e) {
            logger.error("Failed to create session assets directory: {}", e.getMessage());
        }
    }

    /**
     * Set the signal collector for emitting improvement signals on bookmark/rate.
     * Optional: if not set, no signals are emitted (backward compatible).
     */
    public void setSignalCollector(SignalCollector collector) {
        this.signalCollector = collector;
    }

    @Override
    public PageResult<SessionAsset> querySessions(String tenantId, String userId,
                                                   SessionQuery query, int page, int size) {
        List<SessionAsset> all = loadAllForTenant(tenantId);

        // Filter by userId
        if (userId != null) {
            all = all.stream().filter(a -> userId.equals(a.userId()) || a.userId() == null).toList();
        }

        // Filter by status
        if (query.status() != null) {
            SessionAsset.SessionStatus status = SessionAsset.SessionStatus.valueOf(query.status());
            all = all.stream().filter(a -> a.status() == status).toList();
        }

        // Filter bookmarked
        if (Boolean.TRUE.equals(query.bookmarkedOnly())) {
            all = all.stream().filter(SessionAsset::bookmarked).toList();
        }

        // Filter by min rating
        if (query.minRating() != null) {
            all = all.stream().filter(a -> a.rating() >= query.minRating()).toList();
        }

        // Filter by tag
        if (query.tag() != null) {
            all = all.stream().filter(a -> a.tags() != null && a.tags().contains(query.tag())).toList();
        }

        // Filter by time range
        if (query.startTime() != null) {
            all = all.stream().filter(a -> a.createdAt() >= query.startTime()).toList();
        }
        if (query.endTime() != null) {
            all = all.stream().filter(a -> a.createdAt() <= query.endTime()).toList();
        }

        // Sort
        String orderBy = query.orderBy() != null ? query.orderBy() : "updated";
        all = switch (orderBy) {
            case "created" -> all.stream().sorted(Comparator.comparingLong(SessionAsset::createdAt).reversed()).toList();
            case "rating" -> all.stream().sorted(Comparator.comparingInt(SessionAsset::rating).reversed()).toList();
            default -> all.stream().sorted(Comparator.comparingLong(SessionAsset::updatedAt).reversed()).toList();
        };

        // Paginate
        int total = all.size();
        int from = page * size;
        int to = Math.min(from + size, total);
        List<SessionAsset> pageItems = from < total ? all.subList(from, to) : List.of();

        return new PageResult<>(pageItems, page, size, total, to < total);
    }

    @Override
    public SessionAsset getAsset(String tenantId, String sessionId) {
        String key = tenantId + ":" + sessionId;
        SessionAsset cached = cache.get(key);
        if (cached != null) return cached;

        Path file = assetFile(tenantId, sessionId);
        if (Files.exists(file)) {
            try {
                SessionAsset asset = fromJson(mapper.readTree(file.toFile()));
                cache.put(key, asset);
                return asset;
            } catch (IOException e) {
                logger.error("Failed to load session asset {}/{}: {}", tenantId, sessionId, e.getMessage());
            }
        }
        return null;
    }

    @Override
    public List<SessionAsset> searchSessions(String tenantId, String userId, String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        List<SessionAsset> results = new ArrayList<>();
        for (SessionAsset asset : loadAllForTenant(tenantId)) {
            if (userId != null && asset.userId() != null && !userId.equals(asset.userId())) continue;
            if (containsKeyword(asset, lowerKeyword)) {
                results.add(asset);
            }
        }
        return results;
    }

    @Override
    public void bookmark(String tenantId, String userId, String sessionId, String note) {
        SessionAsset existing = getOrCreate(tenantId, userId, sessionId);
        SessionAsset updated = new SessionAsset(
                existing.id(), existing.tenantId(), existing.userId(), existing.sessionId(),
                existing.title(), existing.summary(), existing.status(),
                true, existing.rating(),
                note != null && !note.isBlank() ? note : existing.userComment(),
                existing.tags(), existing.steps(),
                existing.createdAt(), System.currentTimeMillis(), existing.completedAt()
        );
        saveAsset(updated);
        if (signalCollector != null) {
            signalCollector.onBookmark(tenantId, userId, sessionId, note);
        }
    }

    @Override
    public void unbookmark(String tenantId, String userId, String sessionId) {
        SessionAsset existing = getAsset(tenantId, sessionId);
        if (existing == null) return;
        SessionAsset updated = new SessionAsset(
                existing.id(), existing.tenantId(), existing.userId(), existing.sessionId(),
                existing.title(), existing.summary(), existing.status(),
                false, existing.rating(), existing.userComment(),
                existing.tags(), existing.steps(),
                existing.createdAt(), System.currentTimeMillis(), existing.completedAt()
        );
        saveAsset(updated);
    }

    @Override
    public void rate(String tenantId, String userId, String sessionId, int rating, String comment) {
        if (rating < 0 || rating > 5) throw new IllegalArgumentException("Rating must be 0-5");
        SessionAsset existing = getOrCreate(tenantId, userId, sessionId);
        SessionAsset updated = new SessionAsset(
                existing.id(), existing.tenantId(), existing.userId(), existing.sessionId(),
                existing.title(), existing.summary(), existing.status(),
                existing.bookmarked(), rating,
                comment != null && !comment.isBlank() ? comment : existing.userComment(),
                existing.tags(), existing.steps(),
                existing.createdAt(), System.currentTimeMillis(), existing.completedAt()
        );
        saveAsset(updated);
        if (signalCollector != null) {
            if (rating >= 4) {
                signalCollector.onRatingHigh(tenantId, userId, sessionId, rating);
            } else if (rating <= 2 && rating > 0) {
                signalCollector.onRatingLow(tenantId, userId, sessionId, rating);
            }
        }
    }

    @Override
    public void updateAsset(String tenantId, String sessionId, SessionAssetUpdate update) {
        SessionAsset existing = getAsset(tenantId, sessionId);
        if (existing == null) return;
        SessionAsset updated = new SessionAsset(
                existing.id(), existing.tenantId(), existing.userId(), existing.sessionId(),
                update.title() != null ? update.title() : existing.title(),
                existing.summary(),
                update.status() != null ? update.status() : existing.status(),
                existing.bookmarked(), existing.rating(),
                update.userComment() != null ? update.userComment() : existing.userComment(),
                update.tags() != null ? update.tags() : existing.tags(),
                existing.steps(),
                existing.createdAt(), System.currentTimeMillis(), existing.completedAt()
        );
        saveAsset(updated);
    }

    @Override
    public String saveAsset(SessionAsset asset) {
        String assetId = asset.id() != null ? asset.id()
                : "sa_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        SessionAsset withId = new SessionAsset(
                assetId, asset.tenantId(), asset.userId(), asset.sessionId(),
                asset.title(), asset.summary(), asset.status(),
                asset.bookmarked(), asset.rating(), asset.userComment(),
                asset.tags(), asset.steps(),
                asset.createdAt() != 0 ? asset.createdAt() : System.currentTimeMillis(),
                System.currentTimeMillis(), asset.completedAt()
        );

        Path file = assetFile(asset.tenantId(), asset.sessionId());
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), toJson(withId));
            cache.put(asset.tenantId() + ":" + asset.sessionId(), withId);
            logger.debug("Saved session asset: {}/{}", asset.tenantId(), asset.sessionId());
        } catch (IOException e) {
            logger.error("Failed to save session asset: {}", e.getMessage());
        }
        return assetId;
    }

    @Override
    public List<SessionAsset.StepSummary> getSteps(String tenantId, String sessionId) {
        SessionAsset asset = getAsset(tenantId, sessionId);
        return asset != null && asset.steps() != null ? asset.steps() : List.of();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ══════════════════════════════════════════════════════════════════

    private SessionAsset getOrCreate(String tenantId, String userId, String sessionId) {
        SessionAsset existing = getAsset(tenantId, sessionId);
        if (existing != null) return existing;
        // Create a minimal asset
        return new SessionAsset(
                null, tenantId, userId, sessionId,
                null, null, SessionAsset.SessionStatus.ACTIVE,
                false, 0, null, List.of(), List.of(),
                System.currentTimeMillis(), System.currentTimeMillis(), null
        );
    }

    private List<SessionAsset> loadAllForTenant(String tenantId) {
        Path tenantDir = sessionsRoot.resolve(tenantId);
        if (!Files.exists(tenantDir)) return List.of();
        List<SessionAsset> assets = new ArrayList<>();
        try (Stream<Path> stream = Files.list(tenantDir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    assets.add(fromJson(mapper.readTree(p.toFile())));
                } catch (IOException e) {
                    logger.warn("Failed to read session asset: {}", p);
                }
            });
        } catch (IOException e) {
            logger.error("Failed to list session assets for tenant {}: {}", tenantId, e.getMessage());
        }
        return assets;
    }

    private boolean containsKeyword(SessionAsset asset, String keyword) {
        if (asset.title() != null && asset.title().toLowerCase().contains(keyword)) return true;
        if (asset.summary() != null && asset.summary().toLowerCase().contains(keyword)) return true;
        if (asset.userComment() != null && asset.userComment().toLowerCase().contains(keyword)) return true;
        if (asset.tags() != null) {
            for (String tag : asset.tags()) {
                if (tag.toLowerCase().contains(keyword)) return true;
            }
        }
        if (asset.steps() != null) {
            for (var step : asset.steps()) {
                if (step.action() != null && step.action().toLowerCase().contains(keyword)) return true;
                if (step.result() != null && step.result().toLowerCase().contains(keyword)) return true;
            }
        }
        return false;
    }

    private Path assetFile(String tenantId, String sessionId) {
        // Sanitize sessionId to prevent path traversal
        String safeSessionId = sessionId.replaceAll("[^a-zA-Z0-9_:-]", "_");
        return sessionsRoot.resolve(tenantId).resolve(safeSessionId + ".json");
    }

    private ObjectNode toJson(SessionAsset asset) {
        ObjectNode json = mapper.createObjectNode();
        json.put("id", asset.id());
        json.put("tenantId", asset.tenantId());
        if (asset.userId() != null) json.put("userId", asset.userId());
        json.put("sessionId", asset.sessionId());
        if (asset.title() != null) json.put("title", asset.title());
        if (asset.summary() != null) json.put("summary", asset.summary());
        json.put("status", asset.status().name());
        json.put("bookmarked", asset.bookmarked());
        json.put("rating", asset.rating());
        if (asset.userComment() != null) json.put("userComment", asset.userComment());
        if (asset.tags() != null) {
            ArrayNode tagsArr = json.putArray("tags");
            asset.tags().forEach(tagsArr::add);
        }
        if (asset.steps() != null) {
            ArrayNode stepsArr = json.putArray("steps");
            for (var step : asset.steps()) {
                ObjectNode s = stepsArr.addObject();
                s.put("index", step.index());
                if (step.action() != null) s.put("action", step.action());
                if (step.toolUsed() != null) s.put("toolUsed", step.toolUsed());
                if (step.result() != null) s.put("result", step.result());
                s.put("keyStep", step.keyStep());
                s.put("timestamp", step.timestamp());
            }
        }
        json.put("createdAt", asset.createdAt());
        json.put("updatedAt", asset.updatedAt());
        if (asset.completedAt() != null) json.put("completedAt", asset.completedAt());
        return json;
    }

    private SessionAsset fromJson(com.fasterxml.jackson.databind.JsonNode json) {
        List<String> tags = new ArrayList<>();
        if (json.has("tags")) {
            for (var t : json.get("tags")) tags.add(t.asText());
        }
        List<SessionAsset.StepSummary> steps = new ArrayList<>();
        if (json.has("steps")) {
            for (var s : json.get("steps")) {
                steps.add(new SessionAsset.StepSummary(
                        s.path("index").asInt(),
                        s.path("action").asText(null),
                        s.path("toolUsed").asText(null),
                        s.path("result").asText(null),
                        s.path("keyStep").asBoolean(false),
                        s.path("timestamp").asLong()
                ));
            }
        }
        return new SessionAsset(
                json.path("id").asText(null),
                json.path("tenantId").asText(),
                json.path("userId").asText(null),
                json.path("sessionId").asText(),
                json.path("title").asText(null),
                json.path("summary").asText(null),
                SessionAsset.SessionStatus.valueOf(json.path("status").asText("ACTIVE")),
                json.path("bookmarked").asBoolean(false),
                json.path("rating").asInt(0),
                json.path("userComment").asText(null),
                tags,
                steps,
                json.path("createdAt").asLong(),
                json.path("updatedAt").asLong(),
                json.has("completedAt") ? json.get("completedAt").asLong() : null
        );
    }
}
