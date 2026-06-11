package com.nousresearch.hermes.browser.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of running the BrowserBridge contract verifier against a provider. */
public record BrowserBridgeContractReport(boolean ok, String endpoint, List<Check> checks) {
    public record Check(String name, boolean ok, String message, Map<String, Object> details) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("ok", ok);
            map.put("message", message);
            map.put("details", details != null ? details : Map.of());
            return map;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", ok);
        map.put("endpoint", endpoint);
        map.put("checks", checks.stream().map(Check::toMap).toList());
        return map;
    }

    public static Builder builder(String endpoint) { return new Builder(endpoint); }

    public static final class Builder {
        private final String endpoint;
        private final List<Check> checks = new ArrayList<>();

        private Builder(String endpoint) { this.endpoint = endpoint; }

        public Builder pass(String name, String message) {
            return pass(name, message, Map.of());
        }

        public Builder pass(String name, String message, Map<String, Object> details) {
            checks.add(new Check(name, true, message, details));
            return this;
        }

        public Builder fail(String name, String message) {
            return fail(name, message, Map.of());
        }

        public Builder fail(String name, String message, Map<String, Object> details) {
            checks.add(new Check(name, false, message, details));
            return this;
        }

        public BrowserBridgeContractReport build() {
            return new BrowserBridgeContractReport(checks.stream().allMatch(Check::ok), endpoint, List.copyOf(checks));
        }
    }
}
