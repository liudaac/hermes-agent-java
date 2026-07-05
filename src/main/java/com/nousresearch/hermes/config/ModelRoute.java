package com.nousresearch.hermes.config;

import java.util.Objects;

/**
 * S1-3: per-client model route 别名映射。
 *
 * <p>一个 hermes 部署对多个客户 API，用别名映射不同模型/provider。
 * 配置示例（config.yaml）：</p>
 *
 * <pre>{@code
 * model_routes:
 *   - alias: "gpt-4"
 *     model: "gpt-4o"
 *     provider: "openai"
 *     base-url: "https://api.openai.com/v1"
 *   - alias: "claude"
 *     model: "anthropic/claude-3.5-sonnet"
 *     provider: "openrouter"
 *   - alias: "doubao"
 *     model: "doubao-pro-32k"
 *     provider: "volcengine"
 *     base-url: "https://ark.cn-beijing.volces.com/api/v3"
 * }</pre>
 *
 * <p>api_key 不在 route 里配置，走 provider 侧常规凭证解析。
 * 调用方的 API key 用于鉴权，不当模型凭证。</p>
 */
public class ModelRoute {

    private final String alias;       // 别名，如 "gpt-4"
    private final String model;       // 实际模型名，如 "gpt-4o"
    private final String provider;    // provider，如 "openai"
    private final String baseUrl;     // 可选：自定义 base URL

    public ModelRoute(String alias, String model, String provider, String baseUrl) {
        this.alias = Objects.requireNonNull(alias, "alias cannot be null").toLowerCase();
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.provider = provider != null && !provider.isBlank() ? provider : null;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : null;
    }

    public String getAlias() { return alias; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }

    @Override
    public String toString() {
        return "ModelRoute{alias='" + alias + "', model='" + model + "'" +
            (provider != null ? ", provider='" + provider + "'" : "") +
            (baseUrl != null ? ", baseUrl='" + baseUrl + "'" : "") +
            "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelRoute that)) return false;
        return Objects.equals(alias, that.alias) &&
            Objects.equals(model, that.model) &&
            Objects.equals(provider, that.provider) &&
            Objects.equals(baseUrl, that.baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, model, provider, baseUrl);
    }
}
