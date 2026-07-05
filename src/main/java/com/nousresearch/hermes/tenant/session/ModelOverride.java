package com.nousresearch.hermes.tenant.session;

import java.util.Map;
import java.util.Objects;

/**
 * S1-2: 会话级模型覆盖持久化值对象。
 *
 * <p>只存 model / provider / base_url，**绝不存 api_key**。
 * 凭证走 provider 常规解析，不落盘。</p>
 *
 * <p>存储位置：SessionData.metadata["model_override"]</p>
 */
public class ModelOverride {

    private final String model;
    private final String provider;
    private final String baseUrl;

    public ModelOverride(String model, String provider, String baseUrl) {
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.provider = provider != null && !provider.isBlank() ? provider : null;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : null;
    }

    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }

    /**
     * 序列化为 Map，用于存入 SessionData.metadata。
     * 只包含安全字段，绝不包含 api_key。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("model", model);
        if (provider != null) m.put("provider", provider);
        if (baseUrl != null) m.put("base_url", baseUrl);
        return m;
    }

    /**
     * 从 Map 反序列化（经 sanitize 后安全调用）。
     */
    @SuppressWarnings("unchecked")
    public static ModelOverride fromMap(Map<String, Object> map) {
        if (map == null) return null;
        String model = getStr(map, "model");
        if (model == null || model.isBlank()) return null;
        String provider = getStr(map, "provider");
        String baseUrl = getStr(map, "base_url");
        return new ModelOverride(model, provider, baseUrl);
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    @Override
    public String toString() {
        return "ModelOverride{model='" + model + "'" +
            (provider != null ? ", provider='" + provider + "'" : "") +
            (baseUrl != null ? ", baseUrl='" + baseUrl + "'" : "") +
            "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelOverride that)) return false;
        return Objects.equals(model, that.model) &&
            Objects.equals(provider, that.provider) &&
            Objects.equals(baseUrl, that.baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, provider, baseUrl);
    }
}
