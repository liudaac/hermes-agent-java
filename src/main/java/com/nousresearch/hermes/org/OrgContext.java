package com.nousresearch.hermes.org;

import com.nousresearch.hermes.auth.UserIdentityResolver;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.org.compliance.ComplianceFramework;
import com.nousresearch.hermes.org.knowledge.OrganizationalKnowledgeBase;
import com.nousresearch.hermes.org.market.AgentMarketplace;
import com.nousresearch.hermes.org.observe.AgentObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Organization-level context - the top layer of the three-layer main line.
 *
 * <p>Holds organization-wide concerns that apply across all spaces:
 * <ul>
 *   <li>{@link ModelCatalog} - available providers/models and global rate limits</li>
 *   <li>{@link UserIdentityResolver} - cross-channel identity normalization</li>
 *   <li>{@link ComplianceFramework} - org-wide compliance policies</li>
 *   <li>{@link AgentMarketplace} - template distribution across spaces</li>
 *   <li>{@link OrganizationalKnowledgeBase} - org-wide knowledge</li>
 *   <li>{@link AgentObservability} - cross-space observability</li>
 * </ul></p>
 *
 * <p>OrgContext is typically a singleton per deployment. It is consumed
 * by the message flow to resolve identity and enforce compliance before
 * delegating to the space layer.</p>
 */
public class OrgContext {

    private static final Logger logger = LoggerFactory.getLogger(OrgContext.class);

    private final HermesConfig config;
    private ModelCatalog modelCatalog;
    private UserIdentityResolver identityResolver;
    private ComplianceFramework compliance;
    private AgentMarketplace marketplace;
    private OrganizationalKnowledgeBase knowledge;
    private AgentObservability observability;
    private final Map<String, Object> billingSummary = new LinkedHashMap<>();

    public OrgContext(HermesConfig config) {
        this.config = config;
        this.modelCatalog = new ModelCatalog();
        initModelCatalog();
    }

    // ── Model Catalog ──

    public ModelCatalog modelCatalog() { return modelCatalog; }

    private void initModelCatalog() {
        // Load model routes from config as the provider catalog
        try {
            var routes = config.getModelRoutes();
            if (routes != null) {
                for (var route : routes) {
                    String id = route.getProvider();
                    String name = id;
                    String baseUrl = route.getBaseUrl();
                    List<String> models = List.of(route.getModel());
                    modelCatalog.addProvider(new ModelCatalog.ProviderEntry(id, name, baseUrl, models));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize model catalog: {}", e.getMessage());
        }
    }

    // ── Identity ──

    public UserIdentityResolver identityResolver() { return identityResolver; }
    public void setIdentityResolver(UserIdentityResolver r) { this.identityResolver = r; }

    // ── Compliance ──

    public ComplianceFramework compliance() { return compliance; }
    public void setCompliance(ComplianceFramework c) { this.compliance = c; }

    // ── Marketplace ──

    public AgentMarketplace marketplace() { return marketplace; }
    public void setMarketplace(AgentMarketplace m) { this.marketplace = m; }

    // ── Knowledge ──

    public OrganizationalKnowledgeBase knowledge() { return knowledge; }
    public void setKnowledge(OrganizationalKnowledgeBase k) { this.knowledge = k; }

    // ── Observability ──

    public AgentObservability observability() { return observability; }
    public void setObservability(AgentObservability o) { this.observability = o; }

    // ── Billing ──

    public Map<String, Object> billingSummary() { return billingSummary; }

    public void recordBilling(String spaceId, String userId, long tokens, double cost) {
        String key = spaceId + ":" + userId;
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) billingSummary.computeIfAbsent(key,
            k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("spaceId", spaceId);
                m.put("userId", userId);
                m.put("totalTokens", 0L);
                m.put("totalCost", 0.0);
                return m;
            });
        entry.put("totalTokens", ((Number) entry.get("totalTokens")).longValue() + tokens);
        entry.put("totalCost", ((Number) entry.get("totalCost")).doubleValue() + cost);
    }

    // ── Serialization ──

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modelCatalog", modelCatalog.toMap());
        m.put("billingSummary", new LinkedHashMap<>(billingSummary));
        return m;
    }
}
