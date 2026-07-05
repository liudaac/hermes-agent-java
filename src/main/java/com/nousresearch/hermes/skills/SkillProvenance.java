package com.nousresearch.hermes.skills;

/**
 * S5-1: Skill 来源追溯。
 *
 * <p>对齐原版 tools/skill_provenance.py：
 * 每个 skill 存 provenance 字段，标记它是怎么来的。</p>
 */
public enum SkillProvenance {
    /** 用户手动创建 */
    USER,
    /** Agent 自动创建（从 trajectory 中提取） */
    AGENT,
    /** 从外部导入（clawhub / skillhub） */
    IMPORT,
    /** 预打包（系统自带） */
    BUNDLED;

    public static SkillProvenance fromString(String s) {
        if (s == null) return USER;
        return switch (s.toUpperCase()) {
            case "USER" -> USER;
            case "AGENT" -> AGENT;
            case "IMPORT" -> IMPORT;
            case "BUNDLED" -> BUNDLED;
            default -> USER;
        };
    }
}
