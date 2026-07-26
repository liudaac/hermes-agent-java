package com.nousresearch.hermes.config.versioning;

import java.time.Instant;
import java.util.Map;

/**
 * P3: A snapshot of a tenant's configuration at a point in time.
 *
 * <p>Every config change creates a versioned snapshot. Snapshots can be
 * rolled back, compared, or audited.</p>
 *
 * @param versionId     unique version ID (ver_xxx)
 * @param tenantId      tenant this version belongs to
 * @param versionNumber monotonic version number (1, 2, 3, ...)
 * @param config        the full config snapshot (Map form)
 * @param changedBy     who made the change (userId or systemId)
 * @param changeReason  human-readable reason for the change
 * @param createdAt     when this version was created
 */
public record ConfigVersion(
        String versionId,
        String tenantId,
        int versionNumber,
        Map<String, Object> config,
        String changedBy,
        String changeReason,
        Instant createdAt
) {
    public Map<String, Object> toApi() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("versionId", versionId);
        m.put("tenantId", tenantId);
        m.put("versionNumber", versionNumber);
        m.put("changedBy", changedBy);
        m.put("changeReason", changeReason);
        m.put("createdAt", createdAt != null ? createdAt.toString() : null);
        m.put("configKeys", config != null ? config.keySet() : java.util.Set.of());
        return m;
    }
}
