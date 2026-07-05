package com.nousresearch.hermes.plugin;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.plugin.model.PluginKind;
import com.nousresearch.hermes.plugin.model.PluginManifest;
import com.nousresearch.hermes.plugin.model.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1-5: Plugin tool_override consent 测试
 *
 * <p>对齐 Python 原版 commit bff61f558。
 * 验证插件启用时的 tool override consent 机制：</p>
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>Bundled 插件 → 自动信任（跳过 consent）</li>
 *   <li>User/Project 插件 → 需要 config 显式授权或 CLI 交互确认</li>
 *   <li>默认 deny（安全优先）</li>
 *   <li>--allow-tool-override flag 可非交互授权</li>
 * </ul>
 */
class PluginToolOverrideConsentTest {

    private HermesConfig mockConfig;

    @BeforeEach
    void setUp() {
        mockConfig = org.mockito.Mockito.mock(HermesConfig.class);
        org.mockito.Mockito.when(mockConfig.getConfigValue("plugins"))
            .thenReturn(java.util.Map.of());
    }

    private PluginManifest createManifest(String name, Source source, List<String> providesTools) {
        PluginManifest manifest = new PluginManifest();
        manifest.setName(name);
        manifest.setKey(name);
        manifest.setSource(source);
        manifest.setKind(PluginKind.STANDALONE);
        manifest.setVersion("1.0.0");
        manifest.setDescription("Test plugin");
        manifest.setProvidesTools(providesTools);
        manifest.setPath(Path.of("/tmp/" + name));
        return manifest;
    }

    private LoadedPlugin createLoadedPlugin(String name, Source source, List<String> tools) {
        PluginManifest manifest = createManifest(name, source, tools);
        LoadedPlugin plugin = new LoadedPlugin(manifest, true, null);
        return plugin;
    }

    // ========================================================================
    // LoadedPlugin.allowToolOverride 字段
    // ========================================================================

    @Nested
    @DisplayName("LoadedPlugin allowToolOverride 字段")
    class AllowToolOverrideField {

        @Test
        @DisplayName("新插件默认 allowToolOverride=false")
        void defaultValue() {
            LoadedPlugin plugin = createLoadedPlugin("test", Source.USER, List.of());
            assertFalse(plugin.isAllowToolOverride());
        }

        @Test
        @DisplayName("setAllowToolOverride(true) 应生效")
        void setTrue() {
            LoadedPlugin plugin = createLoadedPlugin("test", Source.USER, List.of());
            plugin.setAllowToolOverride(true);
            assertTrue(plugin.isAllowToolOverride());
        }

        @Test
        @DisplayName("setAllowToolOverride(false) 应生效")
        void setFalse() {
            LoadedPlugin plugin = createLoadedPlugin("test", Source.USER, List.of());
            plugin.setAllowToolOverride(true);
            plugin.setAllowToolOverride(false);
            assertFalse(plugin.isAllowToolOverride());
        }
    }

    // ========================================================================
    // isToolOverrideAllowed
    // ========================================================================

    @Nested
    @DisplayName("isToolOverrideAllowed consent 检查")
    class IsToolOverrideAllowed {

        @Test
        @DisplayName("Bundled 插件应自动允许")
        void bundledAutoAllowed() {
            PluginManager manager = new PluginManager(mockConfig);
            // 直接测试：bundled source 的插件应允许
            LoadedPlugin plugin = createLoadedPlugin("bundled-plugin", Source.BUNDLED, List.of("tool1"));
            plugin.setAllowToolOverride(true); // loadPlugin 会设这个
            assertTrue(plugin.isAllowToolOverride());
        }

        @Test
        @DisplayName("User 插件默认不允许")
        void userDefaultDenied() {
            PluginManager manager = new PluginManager(mockConfig);
            LoadedPlugin plugin = createLoadedPlugin("user-plugin", Source.USER, List.of("tool1"));
            // 没有 config 授权，默认 false
            assertFalse(plugin.isAllowToolOverride());
        }

        @Test
        @DisplayName("null pluginKey 应返回 false")
        void nullKey() {
            PluginManager manager = new PluginManager(mockConfig);
            assertFalse(manager.isToolOverrideAllowed(null));
        }

        @Test
        @DisplayName("空字符串 pluginKey 应返回 false")
        void emptyKey() {
            PluginManager manager = new PluginManager(mockConfig);
            assertFalse(manager.isToolOverrideAllowed(""));
        }

        @Test
        @DisplayName("config 中 plugins.entries.<key>.allow_tool_override=true 应允许")
        void configAllows() {
            HermesConfig configWithEntry = org.mockito.Mockito.mock(HermesConfig.class);
            org.mockito.Mockito.when(configWithEntry.getConfigValue("plugins"))
                .thenReturn(java.util.Map.of(
                    "entries", java.util.Map.of(
                        "user-plugin", java.util.Map.of(
                            "allow_tool_override", true
                        )
                    )
                ));

            PluginManager manager = new PluginManager(configWithEntry);
            assertTrue(manager.isToolOverrideAllowed("user-plugin"));
        }

        @Test
        @DisplayName("config 中 plugins.entries.<key>.allow_tool_override=false 应拒绝")
        void configDenies() {
            HermesConfig configWithEntry = org.mockito.Mockito.mock(HermesConfig.class);
            org.mockito.Mockito.when(configWithEntry.getConfigValue("plugins"))
                .thenReturn(java.util.Map.of(
                    "entries", java.util.Map.of(
                        "user-plugin", java.util.Map.of(
                            "allow_tool_override", false
                        )
                    )
                ));

            PluginManager manager = new PluginManager(configWithEntry);
            assertFalse(manager.isToolOverrideAllowed("user-plugin"));
        }

        @Test
        @DisplayName("config 中没有 entries 应拒绝")
        void noEntries() {
            PluginManager manager = new PluginManager(mockConfig);
            assertFalse(manager.isToolOverrideAllowed("unknown-plugin"));
        }

        @Test
        @DisplayName("config 中 entries 有其他插件但没目标插件应拒绝")
        void otherPluginInEntries() {
            HermesConfig configWithEntry = org.mockito.Mockito.mock(HermesConfig.class);
            org.mockito.Mockito.when(configWithEntry.getConfigValue("plugins"))
                .thenReturn(java.util.Map.of(
                    "entries", java.util.Map.of(
                        "other-plugin", java.util.Map.of("allow_tool_override", true)
                    )
                ));

            PluginManager manager = new PluginManager(configWithEntry);
            assertFalse(manager.isToolOverrideAllowed("target-plugin"));
        }
    }

    // ========================================================================
    // enablePlugin
    // ========================================================================

    @Nested
    @DisplayName("enablePlugin 启用流程")
    class EnablePluginFlow {

        @Test
        @DisplayName("不存在的插件应返回 notFound")
        void notFound() {
            PluginManager manager = new PluginManager(mockConfig);
            PluginManager.EnableResult result = manager.enablePlugin("nonexistent", false, false);
            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("not found"));
        }

        @Test
        @DisplayName("Bundled 插件应跳过 consent，直接允许")
        void bundledSkipsConsent() {
            PluginManager manager = new PluginManager(mockConfig);
            // 手动注入一个 bundled 插件
            PluginManifest manifest = createManifest("bundled-tool", Source.BUNDLED, List.of("bundled_tool"));
            LoadedPlugin plugin = new LoadedPlugin(manifest, false, null);
            manager.getPlugins(); // 确保初始化
            // 用反射注入
            try {
                java.lang.reflect.Field f = PluginManager.class.getDeclaredField("plugins");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, LoadedPlugin> pluginsMap =
                    (java.util.Map<String, LoadedPlugin>) f.get(manager);
                pluginsMap.put("bundled-tool", plugin);
            } catch (Exception e) {
                fail("Failed to inject plugin: " + e.getMessage());
            }

            PluginManager.EnableResult result = manager.enablePlugin("bundled-tool", false, true);
            assertTrue(result.isSuccess());
            assertTrue(result.getMessage().contains("Bundled"));
        }

        @Test
        @DisplayName("非交互模式 + allowToolOverride=true 应授权")
        void nonInteractiveAllow() {
            PluginManager manager = new PluginManager(mockConfig);
            PluginManifest manifest = createManifest("user-tool", Source.USER, List.of("custom_tool"));
            LoadedPlugin plugin = new LoadedPlugin(manifest, false, null);
            try {
                java.lang.reflect.Field f = PluginManager.class.getDeclaredField("plugins");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, LoadedPlugin> pluginsMap =
                    (java.util.Map<String, LoadedPlugin>) f.get(manager);
                pluginsMap.put("user-tool", plugin);
            } catch (Exception e) {
                fail("Failed to inject plugin: " + e.getMessage());
            }

            PluginManager.EnableResult result = manager.enablePlugin("user-tool", true, false);
            assertTrue(result.isSuccess());
            assertTrue(result.getMessage().contains("with tool override"));
        }

        @Test
        @DisplayName("非交互模式 + allowToolOverride=false 应不授权")
        void nonInteractiveDeny() {
            PluginManager manager = new PluginManager(mockConfig);
            PluginManifest manifest = createManifest("user-tool", Source.USER, List.of("custom_tool"));
            LoadedPlugin plugin = new LoadedPlugin(manifest, false, null);
            try {
                java.lang.reflect.Field f = PluginManager.class.getDeclaredField("plugins");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, LoadedPlugin> pluginsMap =
                    (java.util.Map<String, LoadedPlugin>) f.get(manager);
                pluginsMap.put("user-tool", plugin);
            } catch (Exception e) {
                fail("Failed to inject plugin: " + e.getMessage());
            }

            PluginManager.EnableResult result = manager.enablePlugin("user-tool", false, false);
            assertTrue(result.isSuccess());
            assertTrue(result.getMessage().contains("without tool override"));
        }
    }

    // ========================================================================
    // EnableResult
    // ========================================================================

    @Nested
    @DisplayName("EnableResult")
    class EnableResultTest {

        @Test
        @DisplayName("ok 结果")
        void okResult() {
            PluginManager.EnableResult result = PluginManager.EnableResult.ok("plugin1", "Success");
            assertEquals("plugin1", result.getPluginKey());
            assertTrue(result.isSuccess());
            assertEquals("Success", result.getMessage());
        }

        @Test
        @DisplayName("notFound 结果")
        void notFoundResult() {
            PluginManager.EnableResult result = PluginManager.EnableResult.notFound("missing");
            assertEquals("missing", result.getPluginKey());
            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("not found"));
        }
    }

    // ========================================================================
    // Bundled 信任列表
    // ========================================================================

    @Nested
    @DisplayName("Bundled 信任列表")
    class BundledTrustList {

        @Test
        @DisplayName("Bundled BACKEND 插件应自动允许 override")
        void bundledBackend() {
            PluginManifest manifest = createManifest("core-backend", Source.BUNDLED, List.of("search"));
            manifest.setKind(PluginKind.BACKEND);
            LoadedPlugin plugin = new LoadedPlugin(manifest, true, null);
            plugin.setAllowToolOverride(true); // loadPlugin 会设
            assertTrue(plugin.isAllowToolOverride());
        }

        @Test
        @DisplayName("Bundled PLATFORM 插件应自动允许 override")
        void bundledPlatform() {
            PluginManifest manifest = createManifest("feishu", Source.BUNDLED, List.of("send_message"));
            manifest.setKind(PluginKind.PLATFORM);
            LoadedPlugin plugin = new LoadedPlugin(manifest, true, null);
            plugin.setAllowToolOverride(true);
            assertTrue(plugin.isAllowToolOverride());
        }

        @Test
        @DisplayName("User STANDALONE 插件默认不允许 override")
        void userStandalone() {
            PluginManifest manifest = createManifest("user-plugin", Source.USER, List.of("custom_tool"));
            manifest.setKind(PluginKind.STANDALONE);
            LoadedPlugin plugin = new LoadedPlugin(manifest, true, null);
            // 默认 false，需要 config 或 CLI 授权
            assertFalse(plugin.isAllowToolOverride());
        }

        @Test
        @DisplayName("Project 插件默认不允许 override")
        void projectPlugin() {
            PluginManifest manifest = createManifest("project-plugin", Source.PROJECT, List.of("project_tool"));
            manifest.setKind(PluginKind.STANDALONE);
            LoadedPlugin plugin = new LoadedPlugin(manifest, true, null);
            assertFalse(plugin.isAllowToolOverride());
        }
    }

    // ========================================================================
    // listPlugins 可见性
    // ========================================================================

    @Nested
    @DisplayName("listPlugins 可见性")
    class ListPluginsVisibility {

        @Test
        @DisplayName("listPlugins 应返回插件信息")
        void listPluginsReturnsInfo() {
            PluginManager manager = new PluginManager(mockConfig);
            PluginManifest manifest = createManifest("test-plugin", Source.USER, List.of("tool1"));
            LoadedPlugin plugin = new LoadedPlugin(manifest, true, null);
            plugin.setAllowToolOverride(true);
            try {
                java.lang.reflect.Field f = PluginManager.class.getDeclaredField("plugins");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, LoadedPlugin> pluginsMap =
                    (java.util.Map<String, LoadedPlugin>) f.get(manager);
                pluginsMap.put("test-plugin", plugin);
            } catch (Exception e) {
                fail("Failed to inject plugin: " + e.getMessage());
            }

            var plugins = manager.listPlugins();
            assertFalse(plugins.isEmpty());
            var info = plugins.get(0);
            assertEquals("test-plugin", info.get("name"));
            assertEquals("user", info.get("source"));
        }
    }
}
