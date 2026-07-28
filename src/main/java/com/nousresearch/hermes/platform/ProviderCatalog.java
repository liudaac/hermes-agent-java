package com.nousresearch.hermes.platform;

import java.util.*;

/**
 * B3: Platform-level Provider Catalog.
 *
 * <p>Controls which AI model providers are available on the platform.
 * Tenants can only use providers that are registered in this catalog.
 * This prevents tenants from configuring arbitrary base_url endpoints
 * (security + compliance).</p>
 *
 * <p>Configuration is loaded from {@code ~/.harness/platform-providers.yaml}:</p>
 * <pre>{@code
 * providers:
 *   - id: openai
 *     display_name: OpenAI
 *     default_base_url: https://api.openai.com/v1
 *     allow_tenant_keys: true
 *     allow_platform_keys: true
 *   - id: ollama-local
 *     display_name: Local Ollama
 *     default_base_url: http://localhost:11434/v1
 *     allow_tenant_keys: false
 *     allow_platform_keys: false
 * }</pre>
 *
 * <p>If no config file exists, built-in defaults are used.</p>
 *
 * @author Hermes Team
 * @version B3
 */
public final class ProviderCatalog {

    private final Map<String, Provider> providers;
    private final List<String> orderedIds;

    private ProviderCatalog(Map<String, Provider> providers, List<String> orderedIds) {
        this.providers = providers;
        this.orderedIds = orderedIds;
    }

    /**
     * Create a catalog with built-in default providers.
     */
    public static ProviderCatalog withDefaults() {
        return builder().addDefaults().build();
    }

    /**
     * Create an empty catalog (for testing or custom config).
     */
    public static ProviderCatalog empty() {
        return new ProviderCatalog(Map.of(), List.of());
    }

    /**
     * Check if a provider is registered.
     */
    public boolean isRegistered(String providerId) {
        if (providerId == null) return false;
        return providers.containsKey(providerId.toLowerCase());
    }

    /**
     * Get a provider by ID.
     * @return the provider, or null if not registered
     */
    public Provider getProvider(String providerId) {
        if (providerId == null) return null;
        return providers.get(providerId.toLowerCase());
    }

    /**
     * Get the default base URL for a provider.
     * @return the default base URL, or null if provider not registered
     */
    public String getDefaultBaseUrl(String providerId) {
        Provider p = getProvider(providerId);
        return p != null ? p.defaultBaseUrl() : null;
    }

    /**
     * Validate that a provider ID is known and allowed for the given key source.
     *
     * @param providerId the provider to check
     * @param keySource "tenant", "platform", or "hybrid"
     * @return true if the provider allows the requested key source
     */
    public boolean isAllowed(String providerId, String keySource) {
        Provider p = getProvider(providerId);
        if (p == null) return false;

        return switch (keySource == null ? "hybrid" : keySource.toLowerCase()) {
            case "tenant" -> p.allowTenantKeys();
            case "platform" -> p.allowPlatformKeys();
            case "hybrid" -> p.allowTenantKeys() || p.allowPlatformKeys();
            default -> false;
        };
    }

    /**
     * Get all registered provider IDs.
     */
    public List<String> listProviderIds() {
        return Collections.unmodifiableList(orderedIds);
    }

    /**
     * Get all registered providers.
     */
    public Collection<Provider> listProviders() {
        return orderedIds.stream()
            .map(providers::get)
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Get or compute the default base URL for a provider, with fallback.
     * If the provider is not in the catalog, returns the openrouter default.
     */
    public String getDefaultBaseUrlOrFallback(String providerId) {
        String url = getDefaultBaseUrl(providerId);
        if (url != null) return url;
        return "https://openrouter.ai/api/v1";
    }

    // ============ Builder ============

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Provider> map = new LinkedHashMap<>();
        private final List<String> order = new ArrayList<>();

        public Builder add(Provider provider) {
            String key = provider.id().toLowerCase();
            if (!map.containsKey(key)) {
                order.add(key);
            }
            map.put(key, provider);
            return this;
        }

        public Builder add(String id, String displayName, String defaultBaseUrl,
                           boolean allowTenantKeys, boolean allowPlatformKeys,
                           List<String> supportedModels) {
            return add(new Provider(id, displayName, defaultBaseUrl,
                allowTenantKeys, allowPlatformKeys, supportedModels));
        }

        public Builder addDefaults() {
            add("openai", "OpenAI", "https://api.openai.com/v1",
                true, true,
                List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini",
                        "o1", "o1-mini", "o3", "o4-mini"));
            add("anthropic", "Anthropic", "https://api.anthropic.com/v1",
                true, true,
                List.of("claude-3-5-sonnet", "claude-3-5-haiku", "claude-3-opus"));
            add("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                true, true, List.of());
            add("deepseek", "DeepSeek", "https://api.deepseek.com/v1",
                true, true,
                List.of("deepseek-chat", "deepseek-reasoner"));
            add("doubao", "Doubao (Volcengine)", "https://ark.cn-beijing.volces.com/api/v3",
                true, true,
                List.of("doubao-pro-32k", "doubao-pro-128k", "doubao-lite-4k"));
            add("moonshot", "Moonshot (Kimi)", "https://api.moonshot.cn/v1",
                true, true,
                List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"));
            add("minimax", "MiniMax", "https://api.minimax.chat/v1",
                true, true,
                List.of("abab6.5s-chat", "abab6.5-chat"));
            add("ollama", "Ollama (Local)", "http://localhost:11434/v1",
                true, false,
                List.of("llama3", "qwen2.5", "mistral"));
            return this;
        }

        public ProviderCatalog build() {
            return new ProviderCatalog(
                Collections.unmodifiableMap(new LinkedHashMap<>(map)),
                Collections.unmodifiableList(new ArrayList<>(order))
            );
        }
    }

    // ============ Provider record ============

    /**
     * A single provider entry in the catalog.
     *
     * @param id               provider ID (e.g. "openai")
     * @param displayName      human-readable name
     * @param defaultBaseUrl   default API base URL
     * @param allowTenantKeys  whether tenants can bring their own API keys
     * @param allowPlatformKeys whether platform-managed keys are available
     * @param supportedModels   list of known supported models (may be empty)
     */
    public record Provider(
            String id,
            String displayName,
            String defaultBaseUrl,
            boolean allowTenantKeys,
            boolean allowPlatformKeys,
            List<String> supportedModels
    ) {
        public Provider {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Provider id cannot be null or blank");
            }
            Objects.requireNonNull(defaultBaseUrl, "defaultBaseUrl cannot be null");
            supportedModels = supportedModels != null
                ? List.copyOf(supportedModels)
                : List.of();
        }
    }
}
