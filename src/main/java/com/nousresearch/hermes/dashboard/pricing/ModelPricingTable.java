package com.nousresearch.hermes.dashboard.pricing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared model pricing table used by the dashboard analytics to compute
 * estimated cost in USD from token counts.
 *
 * Prices are in USD per 1 million tokens for prompt / completion respectively.
 * Defaults come from public list prices and are loaded once; users can override by
 * supplying a JSON file at {@code ~/.hermes/dashboard-model-pricing.json} with the
 * shape:
 *
 * <pre>{@code
 * {
 *   "gpt-4o": { "input": 5.0, "output": 15.0 },
 *   "*": { "input": 1.0, "output": 3.0 }
 * }
 * }</pre>
 */
public final class ModelPricingTable {
    private static final Logger logger = LoggerFactory.getLogger(ModelPricingTable.class);

    private final Map<String, Pricing> pricing = new LinkedHashMap<>();
    private final Pricing fallback;

    public ModelPricingTable() {
        this(null);
    }

    public ModelPricingTable(Path overridePath) {
        applyDefaults();
        Pricing initialFallback = pricing.getOrDefault("default", new Pricing(1.0, 3.0));
        if (overridePath != null && Files.exists(overridePath)) {
            try {
                JSONObject overrides = JSON.parseObject(Files.readString(overridePath));
                for (String key : overrides.keySet()) {
                    JSONObject entry = overrides.getJSONObject(key);
                    if (entry == null) {
                        continue;
                    }
                    double input = entry.getDoubleValue("input");
                    double output = entry.getDoubleValue("output");
                    pricing.put(key.toLowerCase(), new Pricing(input, output));
                }
                if (pricing.containsKey("*")) {
                    initialFallback = pricing.get("*");
                }
            } catch (Exception e) {
                logger.warn("Failed to load dashboard model pricing overrides from {}: {}", overridePath, e.getMessage());
            }
        }
        this.fallback = initialFallback;
    }

    private void applyDefaults() {
        pricing.put("claude-opus-4-20250514", new Pricing(15.0, 75.0));
        pricing.put("claude-sonnet-4-20250514", new Pricing(3.0, 15.0));
        pricing.put("claude-3-5-sonnet", new Pricing(3.0, 15.0));
        pricing.put("claude-3-5-haiku", new Pricing(0.8, 4.0));
        pricing.put("gpt-4o", new Pricing(5.0, 15.0));
        pricing.put("gpt-4o-mini", new Pricing(0.15, 0.6));
        pricing.put("gpt-4.1", new Pricing(2.0, 8.0));
        pricing.put("gpt-4.1-mini", new Pricing(0.4, 1.6));
        pricing.put("o1", new Pricing(15.0, 60.0));
        pricing.put("o1-mini", new Pricing(3.0, 12.0));
        pricing.put("gemini-1.5-pro", new Pricing(3.5, 10.5));
        pricing.put("gemini-1.5-flash", new Pricing(0.075, 0.3));
        pricing.put("default", new Pricing(1.0, 3.0));
    }

    /** Lookup pricing for the given model. Returns the fallback if no entry matches. */
    public Pricing forModel(String model) {
        if (model == null || model.isBlank()) {
            return fallback;
        }
        String key = model.toLowerCase();
        Pricing direct = pricing.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Pricing> entry : pricing.entrySet()) {
            String configured = entry.getKey();
            if (configured.equals("default") || configured.equals("*")) {
                continue;
            }
            if (key.startsWith(configured) || key.contains(configured)) {
                return entry.getValue();
            }
        }
        return fallback;
    }

    /** Estimate cost in USD for the given model and token counts. */
    public double estimateCost(String model, long inputTokens, long outputTokens) {
        Pricing p = forModel(model);
        double inputCost = (Math.max(0, inputTokens) / 1_000_000.0) * p.inputPricePerMillion();
        double outputCost = (Math.max(0, outputTokens) / 1_000_000.0) * p.outputPricePerMillion();
        return inputCost + outputCost;
    }

    public record Pricing(double inputPricePerMillion, double outputPricePerMillion) {}
}
