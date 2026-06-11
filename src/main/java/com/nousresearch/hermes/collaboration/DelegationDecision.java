package com.nousresearch.hermes.collaboration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Advisory result from DelegationPolicy. No external process is launched. */
public record DelegationDecision(
    boolean recommended,
    String reason,
    ContextPressureReport contextPressure,
    String suggestedTeamId,
    String suggestedProfile
) {
    public static DelegationDecision notRecommended(ContextPressureReport report) {
        return new DelegationDecision(false, "delegation not requested or context pressure below threshold", report, null, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delegation_recommended", recommended);
        m.put("delegation_reason", reason);
        m.put("context_pressure", contextPressure != null ? contextPressure.toMap() : ContextPressureReport.none().toMap());
        m.put("suggested_team_id", suggestedTeamId);
        m.put("suggested_profile", suggestedProfile);
        return m;
    }
}
