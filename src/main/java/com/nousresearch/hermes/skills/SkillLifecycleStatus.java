package com.nousresearch.hermes.skills;

/**
 * S5-1: Skill 生命周期状态（Curator 管理）。
 */
public enum SkillLifecycleStatus {
    /** 活跃 — 最近 30 天内使用过 */
    ACTIVE,
    /** 过期 — 超过 30 天未使用 */
    STALE,
    /** 归档 — 超过 90 天未使用（永不删除，可恢复） */
    ARCHIVED
}
