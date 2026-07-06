package com.nousresearch.hermes.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S5-4: Skill Bundle — 一键加载多个 skill。
 *
 * <p>对齐原版 agent/skill_bundles.py。
 * 配置示例：workspace/skill-bundles/coding.yaml</p>
 *
 * <pre>{@code
 * name: coding
 * description: 编程助手技能包
 * skills:
 *   - code-review
 *   - test-generator
 *   - refactoring
 * }</pre>
 *
 * <p>使用：/bundle coding → 加载 3 个 skill</p>
 */
public class SkillBundleService {
    private static final Logger logger = LoggerFactory.getLogger(SkillBundleService.class);

    private final SkillManager skillManager;
    private final Map<String, SkillBundle> bundles = new ConcurrentHashMap<>();

    public SkillBundleService(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    /**
     * 注册一个 skill bundle。
     */
    public void registerBundle(String name, String description, List<String> skillNames) {
        SkillBundle bundle = new SkillBundle(name, description, List.copyOf(skillNames));
        bundles.put(name.toLowerCase(), bundle);
        logger.info("Registered skill bundle: '{}' ({} skills)", name, skillNames.size());
    }

    /**
     * 加载 bundle — 激活 bundle 中的所有 skill。
     *
     * @return 加载结果（成功/失败的 skill 列表）
     */
    public BundleLoadResult load(String bundleName) {
        SkillBundle bundle = bundles.get(bundleName.toLowerCase());
        if (bundle == null) {
            return new BundleLoadResult(bundleName, false, List.of(), List.of(),
                "Bundle not found: " + bundleName);
        }

        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String skillName : bundle.skills()) {
            try {
                // 检查 skill 是否存在
                boolean exists = skillManager.listSkills().stream()
                    .anyMatch(s -> skillName.equals(s.name));
                if (exists) {
                    loaded.add(skillName);
                } else {
                    failed.add(skillName + " (not found)");
                }
            } catch (Exception e) {
                failed.add(skillName + " (" + e.getMessage() + ")");
            }
        }

        logger.info("Bundle '{}' loaded: {} success, {} failed", bundleName, loaded.size(), failed.size());
        return new BundleLoadResult(bundleName, true, loaded, failed, null);
    }

    /**
     * 列出所有已注册的 bundle。
     */
    public List<SkillBundle> listBundles() {
        return new ArrayList<>(bundles.values());
    }

    /**
     * 获取单个 bundle。
     */
    public Optional<SkillBundle> getBundle(String name) {
        return Optional.ofNullable(bundles.get(name.toLowerCase()));
    }

    /**
     * 移除 bundle。
     */
    public boolean removeBundle(String name) {
        return bundles.remove(name.toLowerCase()) != null;
    }

    /**
     * 检查 bundle 是否有冲突（同一 skill 出现在多个 bundle 中）。
     */
    public Map<String, List<String>> findConflicts() {
        Map<String, List<String>> skillToBundles = new HashMap<>();
        for (var bundle : bundles.values()) {
            for (String skill : bundle.skills()) {
                skillToBundles.computeIfAbsent(skill, k -> new ArrayList<>())
                    .add(bundle.name());
            }
        }
        Map<String, List<String>> conflicts = new HashMap<>();
        for (var entry : skillToBundles.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.put(entry.getKey(), entry.getValue());
            }
        }
        return conflicts;
    }

    // ============ 数据类 ============

    public record SkillBundle(String name, String description, List<String> skills) {}

    public record BundleLoadResult(
        String bundleName,
        boolean bundleFound,
        List<String> loadedSkills,
        List<String> failedSkills,
        String errorMessage
    ) {
        public boolean isSuccess() {
            return bundleFound && failedSkills.isEmpty();
        }
    }
}
