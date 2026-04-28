package com.nousresearch.hermes.tenant.core;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 租户 Skill 数据类
 */
public record TenantSkill(
    String name,
    String description,
    String content,
    Set<String> tags,
    Map<String, Object> metadata,
    int version,
    int usageCount,
    Instant createdAt,
    Instant updatedAt,
    String registryId,
    String registryVersion,
    TenantSkillManager.SkillSource source,
    String tenantId,
    boolean readOnly,
    String signature
) {
    public TenantSkill withSource(TenantSkillManager.SkillSource source) {
        return new TenantSkill(
            name, description, content, tags, metadata, version, usageCount,
            createdAt, updatedAt, registryId, registryVersion, source, tenantId,
            readOnly, signature
        );
    }

    public TenantSkill withReadOnly(boolean readOnly) {
        return new TenantSkill(
            name, description, content, tags, metadata, version, usageCount,
            createdAt, updatedAt, registryId, registryVersion, source, tenantId,
            readOnly, signature
        );
    }

    public TenantSkill withContent(String newContent) {
        return new TenantSkill(
            name, description, newContent, tags, metadata, version + 1, usageCount,
            createdAt, Instant.now(), registryId, registryVersion, source, tenantId,
            readOnly, signature
        );
    }
}
