package com.nousresearch.hermes.browser.contract;

import com.nousresearch.hermes.browser.BrowserAction;
import com.nousresearch.hermes.browser.BrowserBridge;
import com.nousresearch.hermes.browser.BrowserBridgeConfig;
import com.nousresearch.hermes.browser.BrowserBridgeFactory;

import java.util.List;
import java.util.Map;

/**
 * Verifies that a BrowserBridge daemon satisfies Hermes BrowserBridge contract v1.
 */
public class BrowserBridgeContractVerifier {
    private final BrowserBridge bridge;
    private final String endpoint;

    public BrowserBridgeContractVerifier(BrowserBridge bridge, String endpoint) {
        this.bridge = bridge;
        this.endpoint = endpoint;
    }

    public static BrowserBridgeContractReport verify(BrowserBridgeConfig config) {
        return new BrowserBridgeContractVerifier(BrowserBridgeFactory.create(config), config.endpoint()).verify();
    }

    public BrowserBridgeContractReport verify() {
        var report = BrowserBridgeContractReport.builder(endpoint);

        Map<String, Object> caps = bridge.capabilities();
        if (Boolean.FALSE.equals(caps.get("ok"))) {
            report.fail("capabilities", "Capabilities endpoint returned not-ok", caps);
        } else if (!String.valueOf(caps.getOrDefault("protocol", "")).equals("hermes.browser.v1")) {
            report.fail("capabilities.protocol", "Expected protocol hermes.browser.v1", caps);
        } else if (!String.valueOf(caps.getOrDefault("actions", "")).contains("open")) {
            report.fail("capabilities.actions", "Capabilities should include open action", caps);
        } else {
            report.pass("capabilities", "Capabilities are compatible", caps);
        }

        var health = bridge.healthCheck();
        if (!health.ok()) {
            report.fail("health", health.message(), health.toMap());
        } else {
            report.pass("health", "Health check succeeded", health.toMap());
        }

        var open = bridge.execute(new BrowserAction("open", null, "https://example.com", null, null, null, "contract-verifier", "verify open action"));
        if (!open.ok()) {
            report.fail("actions.open", open.message(), open.toMap());
        } else if (open.sessionId() == null || open.sessionId().isBlank()) {
            report.fail("actions.open.session", "Open should return a session_id", open.toMap());
        } else {
            report.pass("actions.open", "Open action succeeded", open.toMap());
        }

        var observe = bridge.execute(new BrowserAction("observe", open.sessionId(), null, null, null, null, "contract-verifier", "verify observe action"));
        if (!observe.ok()) {
            report.fail("actions.observe", observe.message(), observe.toMap());
        } else {
            report.pass("actions.observe", "Observe action succeeded", observe.toMap());
        }

        var missing = bridge.execute(new BrowserAction("click", "missing-session", null, "Missing button", null, null, "contract-verifier", "verify missing session classification"));
        if (missing.ok()) {
            report.fail("errors.session_missing", "Missing session click should not succeed", missing.toMap());
        } else if (!List.of("session_missing", "selector_not_found", "provider_error", "bridge_error").contains(missing.errorCode())) {
            report.fail("errors.session_missing", "Unexpected error_code for missing session", missing.toMap());
        } else {
            report.pass("errors.session_missing", "Missing session error is classified", missing.toMap());
        }

        return report.build();
    }
}
