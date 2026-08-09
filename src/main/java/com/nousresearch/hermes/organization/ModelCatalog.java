package com.nousresearch.hermes.organization;

import java.util.*;

/**
 * Organization-level model catalog - which providers and models are
 * available across the entire organization, with global rate limits.
 */
public class ModelCatalog {

    private final List<ProviderEntry> providers = new ArrayList<>();
    private final Map<String, RateLimit> globalRateLimits = new LinkedHashMap<>();  // provider -> limit

    public List<ProviderEntry> providers() { return providers; }
    public Map<String, RateLimit> globalRateLimits() { return globalRateLimits; }

    public void addProvider(ProviderEntry entry) {
        providers.removeIf(p -> p.id().equals(entry.id()));
        providers.add(entry);
    }

    public void removeProvider(String providerId) {
        providers.removeIf(p -> p.id().equals(providerId));
        globalRateLimits.remove(providerId);
    }

    public void setRateLimit(String providerId, int requestsPerMinute) {
        globalRateLimits.put(providerId, new RateLimit(providerId, requestsPerMinute));
    }

    public record ProviderEntry(String id, String displayName, String baseUrl, List<String> models) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("displayName", displayName);
            m.put("baseUrl", baseUrl);
            m.put("models", models);
            return m;
        }
    }

    public record RateLimit(String providerId, int requestsPerMinute) {}

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providers", providers.stream().map(ProviderEntry::toMap).toList());
        m.put("globalRateLimits", new LinkedHashMap<>(globalRateLimits));
        return m;
    }
}
