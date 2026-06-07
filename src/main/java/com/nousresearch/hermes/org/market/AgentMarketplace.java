package com.nousresearch.hermes.org.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent template marketplace for an AI-native organization.
 */
public class AgentMarketplace {
    private static final Logger logger = LoggerFactory.getLogger(AgentMarketplace.class);
    private final ConcurrentHashMap<String, AgentTemplate> templates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> installed = new ConcurrentHashMap<>();

    public AgentMarketplace() {
        for (AgentTemplate t : AgentTemplate.allBuiltin()) register(t);
    }

    public AgentTemplate register(AgentTemplate t) {
        templates.put(t.getId(), t);
        logger.info("Registered template: {} ({})", t.getName(), t.getId());
        return t;
    }

    public boolean unregister(String id) { return templates.remove(id) != null; }

    public Map<String, Object> install(String tenantId, String templateId, Map<String, String> params) {
        AgentTemplate t = get(templateId);
        installed.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet()).add(templateId);
        t.recordInstall();
        Map<String, Object> cfg = t.instantiate(params);
        cfg.put("tenant_id", tenantId);
        return cfg;
    }

    public boolean uninstall(String tenantId, String templateId) {
        Set<String> s = installed.get(tenantId);
        return s != null && s.remove(templateId);
    }

    public void rate(String templateId, int score) {
        get(templateId).recordRating(score);
    }

    public AgentTemplate get(String id) {
        AgentTemplate t = templates.get(id);
        if (t == null) throw new IllegalArgumentException("Unknown template: " + id);
        return t;
    }

    public Optional<AgentTemplate> find(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    public List<AgentTemplate> listAll() { return List.copyOf(templates.values()); }

    public List<AgentTemplate> findByCategory(String cat) {
        return templates.values().stream()
            .filter(t -> t.getCategory().equalsIgnoreCase(cat)).toList();
    }

    public List<AgentTemplate> findByTag(String tag) {
        return templates.values().stream()
            .filter(t -> t.getTags().contains(tag)).toList();
    }

    public List<AgentTemplate> search(String q) {
        String lq = q.toLowerCase();
        return templates.values().stream()
            .filter(t -> t.getName().toLowerCase().contains(lq)
                || t.getDescription().toLowerCase().contains(lq)
                || t.getTags().stream().anyMatch(tg -> tg.contains(lq))).toList();
    }

    public List<AgentTemplate> mostPopular(int n) {
        return templates.values().stream()
            .sorted(Comparator.comparingInt(AgentTemplate::getInstallCount).reversed()).limit(n).toList();
    }

    public List<AgentTemplate> topRated(int n) {
        return templates.values().stream()
            .sorted(Comparator.comparingDouble(AgentTemplate::getRating).reversed()).limit(n).toList();
    }

    public Set<String> getInstalled(String tenantId) {
        return Collections.unmodifiableSet(installed.getOrDefault(tenantId, Set.of()));
    }

    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", templates.size());
        s.put("installs", installed.values().stream().mapToInt(Set::size).sum());
        s.put("popular", mostPopular(3).stream().map(AgentTemplate::getName).toList());
        return s;
    }
}