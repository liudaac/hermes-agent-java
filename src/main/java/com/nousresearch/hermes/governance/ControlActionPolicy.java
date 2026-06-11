package com.nousresearch.hermes.governance;

import java.util.Locale;
import java.util.Set;

/** Lightweight policy gate for high-impact Org Control Center actions. */
public final class ControlActionPolicy {
    public enum Action { REPLAY_INTENT, REROUTE_INTENT, OVERRIDE_AGENT, CONFIGURE_BROWSER_BRIDGE, CHECK_BROWSER_BRIDGE, APPROVE_BROWSER_ACTION, REJECT_BROWSER_ACTION }

    private static final Set<String> FULL_ACCESS = Set.of("dashboard", "operator", "admin", "system");
    private static final Set<String> READ_ONLY = Set.of("viewer", "readonly", "read-only", "guest");

    private ControlActionPolicy() {}

    public static boolean isAllowed(String actor, Action action) {
        String normalized = normalize(actor);
        if (FULL_ACCESS.contains(normalized)) return true;
        if (READ_ONLY.contains(normalized)) return false;
        // Unknown explicit actors are denied by default. Missing actors are normalized to dashboard upstream.
        return false;
    }

    public static String denyReason(String actor, Action action) {
        return "Actor '" + normalize(actor) + "' is not allowed to perform " + action.name();
    }

    private static String normalize(String actor) {
        if (actor == null || actor.isBlank()) return "dashboard";
        return actor.trim().toLowerCase(Locale.ROOT);
    }
}
