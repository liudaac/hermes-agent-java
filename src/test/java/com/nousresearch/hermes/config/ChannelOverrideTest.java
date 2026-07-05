package com.nousresearch.hermes.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1-1: Per-Channel Model + System Prompt Overrides 测试
 *
 * <p>验证 4 层模型解析优先级：
 * session /model > channel override > tenant default > global</p>
 */
class ChannelOverrideTest {

    private HermesConfig config;

    @BeforeEach
    void setUp() throws Exception {
        // HermesConfig(Path) 是 private，用无参构造 + 反射注入 config map
        config = new HermesConfig();
        Map<String, Object> cfgMap = new java.util.HashMap<>();
        
        // global model
        Map<String, Object> modelCfg = new java.util.HashMap<>();
        modelCfg.put("provider", "openrouter");
        modelCfg.put("model", "anthropic/claude-3.5-sonnet");
        modelCfg.put("base_url", "https://openrouter.ai/api/v1");
        cfgMap.put("model", modelCfg);
        
        // channel overrides
        List<Map<String, Object>> overrides = new java.util.ArrayList<>();
        
        Map<String, Object> o1 = new java.util.HashMap<>();
        o1.put("channel", "feishu");
        o1.put("channel-id", "ou_group_123");
        o1.put("model", "doubao-pro-32k");
        o1.put("base-url", "https://ark.cn-beijing.volces.com/api/v3");
        o1.put("system-prompt-suffix", "你是一个飞书助手。");
        overrides.add(o1);
        
        Map<String, Object> o2 = new java.util.HashMap<>();
        o2.put("channel", "feishu");
        o2.put("model", "deepseek-chat");
        o2.put("base-url", "https://api.deepseek.com/v1");
        overrides.add(o2);
        
        Map<String, Object> o3 = new java.util.HashMap<>();
        o3.put("channel", "qqbot");
        o3.put("model", "qwen-max");
        o3.put("base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        o3.put("system-prompt-suffix", "你是一个QQ群助手。");
        overrides.add(o3);
        
        Map<String, Object> o4 = new java.util.HashMap<>();
        o4.put("channel", "discord");
        o4.put("model", "gpt-4o");
        overrides.add(o4);
        
        cfgMap.put("channel-overrides", overrides);
        
        // 反射注入
        java.lang.reflect.Field f = HermesConfig.class.getDeclaredField("config");
        f.setAccessible(true);
        f.set(config, cfgMap);
    }

    // ========================================================================
    // ChannelOverride 值对象
    // ========================================================================

    @Nested
    @DisplayName("ChannelOverride 值对象")
    class ValueObject {

        @Test
        @DisplayName("基本构造和 getter")
        void basicConstruction() {
            ChannelOverride co = new ChannelOverride("feishu", "ou_123", "doubao-pro",
                "https://ark.cn-beijing.volces.com/api/v3", "你是助手");
            assertEquals("feishu", co.getChannel());
            assertEquals("ou_123", co.getChannelId());
            assertEquals("doubao-pro", co.getModel());
            assertEquals("https://ark.cn-beijing.volces.com/api/v3", co.getBaseUrl());
            assertEquals("你是助手", co.getSystemPromptSuffix());
        }

        @Test
        @DisplayName("channel 转小写")
        void channelLowercased() {
            ChannelOverride co = new ChannelOverride("FEISHU", null, "model", null, null);
            assertEquals("feishu", co.getChannel());
        }

        @Test
        @DisplayName("空字符串 channelId 转为 null（通配）")
        void emptyChannelIdBecomesNull() {
            ChannelOverride co = new ChannelOverride("feishu", "", "model", null, null);
            assertNull(co.getChannelId());
        }

        @Test
        @DisplayName("空字符串 baseUrl 转为 null")
        void emptyBaseUrlBecomesNull() {
            ChannelOverride co = new ChannelOverride("feishu", null, "model", "  ", null);
            assertNull(co.getBaseUrl());
        }

        @Test
        @DisplayName("空字符串 systemPromptSuffix 转为 null")
        void emptySuffixBecomesNull() {
            ChannelOverride co = new ChannelOverride("feishu", null, "model", null, "  ");
            assertNull(co.getSystemPromptSuffix());
        }

        @Test
        @DisplayName("null channel 抛 NPE")
        void nullChannelThrows() {
            assertThrows(NullPointerException.class, () -> new ChannelOverride(null, null, "model", null, null));
        }

        @Test
        @DisplayName("null model 抛 NPE")
        void nullModelThrows() {
            assertThrows(NullPointerException.class, () -> new ChannelOverride("feishu", null, null, null, null));
        }
    }

    // ========================================================================
    // matches 匹配逻辑
    // ========================================================================

    @Nested
    @DisplayName("matches 匹配逻辑")
    class MatchesLogic {

        @Test
        @DisplayName("channel 匹配 + channelId 为 null → 通配")
        void wildcardMatch() {
            ChannelOverride co = new ChannelOverride("feishu", null, "model", null, null);
            assertTrue(co.matches("feishu", "any_id"));
            assertTrue(co.matches("feishu", null));
            assertTrue(co.matches("FEISHU", "any_id")); // 大小写不敏感
        }

        @Test
        @DisplayName("channel 匹配 + channelId 精确匹配")
        void exactChannelIdMatch() {
            ChannelOverride co = new ChannelOverride("feishu", "ou_123", "model", null, null);
            assertTrue(co.matches("feishu", "ou_123"));
            assertFalse(co.matches("feishu", "ou_456"));
            assertFalse(co.matches("feishu", null));
        }

        @Test
        @DisplayName("channel 不匹配 → false")
        void channelMismatch() {
            ChannelOverride co = new ChannelOverride("feishu", null, "model", null, null);
            assertFalse(co.matches("qqbot", null));
            assertFalse(co.matches("qqbot", "any_id"));
        }

        @Test
        @DisplayName("null msgChannel → false")
        void nullMsgChannel() {
            ChannelOverride co = new ChannelOverride("feishu", null, "model", null, null);
            assertFalse(co.matches(null, null));
        }
    }

    // ========================================================================
    // resolveModel — 4 层优先级
    // ========================================================================

    @Nested
    @DisplayName("resolveModel 4 层优先级")
    class ResolveModelPriority {

        @Test
        @DisplayName("1. session /model 最高优先")
        void sessionModelWins() {
            String model = config.resolveModel("feishu", "ou_group_123", "custom-session-model");
            assertEquals("custom-session-model", model);
        }

        @Test
        @DisplayName("2. channel override + channelId 精确匹配")
        void channelOverrideWithChannelId() {
            String model = config.resolveModel("feishu", "ou_group_123", null);
            assertEquals("doubao-pro-32k", model);
        }

        @Test
        @DisplayName("2. channel override 通配匹配（无 channelId）")
        void channelOverrideWildcard() {
            // feishu + 其他 channelId → 匹配通配的 deepseek-chat
            String model = config.resolveModel("feishu", "ou_other_group", null);
            assertEquals("deepseek-chat", model);
        }

        @Test
        @DisplayName("2. channel override 通配匹配（msgChannelId=null）")
        void channelOverrideWildcardNullId() {
            String model = config.resolveModel("feishu", null, null);
            assertEquals("deepseek-chat", model);
        }

        @Test
        @DisplayName("3. 无 channel override → global default")
        void globalDefault() {
            String model = config.resolveModel("telegram", null, null);
            assertEquals("anthropic/claude-3.5-sonnet", model);
        }

        @Test
        @DisplayName("3. 未知 channel → global default")
        void unknownChannel() {
            String model = config.resolveModel("slack", null, null);
            assertEquals("anthropic/claude-3.5-sonnet", model);
        }

        @Test
        @DisplayName("channel=null → global default")
        void nullChannel() {
            String model = config.resolveModel(null, null, null);
            assertEquals("anthropic/claude-3.5-sonnet", model);
        }

        @Test
        @DisplayName("session /model 覆盖 channel override")
        void sessionOverridesChannel() {
            String model = config.resolveModel("feishu", "ou_group_123", "my-model");
            assertEquals("my-model", model);
        }

        @Test
        @DisplayName("discord channel override（无 baseUrl）")
        void discordOverride() {
            String model = config.resolveModel("discord", null, null);
            assertEquals("gpt-4o", model);
        }

        @Test
        @DisplayName("qqbot channel override")
        void qqbotOverride() {
            String model = config.resolveModel("qqbot", null, null);
            assertEquals("qwen-max", model);
        }
    }

    // ========================================================================
    // resolveBaseUrl
    // ========================================================================

    @Nested
    @DisplayName("resolveBaseUrl")
    class ResolveBaseUrl {

        @Test
        @DisplayName("channel override 提供 base URL")
        void channelOverrideBaseUrl() {
            String url = config.resolveBaseUrl("feishu", "ou_group_123", null);
            assertEquals("https://ark.cn-beijing.volces.com/api/v3", url);
        }

        @Test
        @DisplayName("channel override 无 base URL → global default")
        void noChannelBaseUrl() {
            String url = config.resolveBaseUrl("discord", null, null);
            // discord override 没有 base-url，应回落到 global
            assertEquals("https://openrouter.ai/api/v1", url);
        }

        @Test
        @DisplayName("无 channel override → global default")
        void noOverride() {
            String url = config.resolveBaseUrl("telegram", null, null);
            assertEquals("https://openrouter.ai/api/v1", url);
        }
    }

    // ========================================================================
    // resolveSystemPromptSuffix
    // ========================================================================

    @Nested
    @DisplayName("resolveSystemPromptSuffix")
    class ResolveSystemPromptSuffix {

        @Test
        @DisplayName("feishu + channelId 有 suffix")
        void feishuWithSuffix() {
            String suffix = config.resolveSystemPromptSuffix("feishu", "ou_group_123");
            assertEquals("你是一个飞书助手。", suffix);
        }

        @Test
        @DisplayName("qqbot 有 suffix")
        void qqbotWithSuffix() {
            String suffix = config.resolveSystemPromptSuffix("qqbot", null);
            assertEquals("你是一个QQ群助手。", suffix);
        }

        @Test
        @DisplayName("feishu 通配无 suffix → null")
        void feishuWildcardNoSuffix() {
            String suffix = config.resolveSystemPromptSuffix("feishu", "ou_other");
            assertNull(suffix);
        }

        @Test
        @DisplayName("discord 无 suffix → null")
        void discordNoSuffix() {
            String suffix = config.resolveSystemPromptSuffix("discord", null);
            assertNull(suffix);
        }

        @Test
        @DisplayName("未知 channel → null")
        void unknownChannel() {
            String suffix = config.resolveSystemPromptSuffix("telegram", null);
            assertNull(suffix);
        }

        @Test
        @DisplayName("null channel → null")
        void nullChannel() {
            String suffix = config.resolveSystemPromptSuffix(null, null);
            assertNull(suffix);
        }
    }

    // ========================================================================
    // getChannelOverrides
    // ========================================================================

    @Nested
    @DisplayName("getChannelOverrides 配置解析")
    class GetChannelOverrides {

        @Test
        @DisplayName("应解析出 4 个 override")
        void correctCount() {
            List<ChannelOverride> overrides = config.getChannelOverrides();
            assertEquals(4, overrides.size());
        }

        @Test
        @DisplayName("第一个 override 应有完整字段")
        void firstOverrideComplete() {
            List<ChannelOverride> overrides = config.getChannelOverrides();
            ChannelOverride first = overrides.get(0);
            assertEquals("feishu", first.getChannel());
            assertEquals("ou_group_123", first.getChannelId());
            assertEquals("doubao-pro-32k", first.getModel());
            assertEquals("https://ark.cn-beijing.volces.com/api/v3", first.getBaseUrl());
            assertEquals("你是一个飞书助手。", first.getSystemPromptSuffix());
        }

        @Test
        @DisplayName("无 channel-overrides 配置 → 空列表")
        void noOverridesConfig() throws Exception {
            HermesConfig emptyConfig = new HermesConfig();
            assertTrue(emptyConfig.getChannelOverrides().isEmpty());
        }
    }

    // ========================================================================
    // 精确匹配优先于通配
    // ========================================================================

    @Nested
    @DisplayName("精确匹配优先于通配")
    class SpecificBeforeWildcard {

        @Test
        @DisplayName("feishu + ou_group_123 → doubao-pro-32k（精确），不是 deepseek-chat（通配）")
        void specificChannelIdWins() {
            // 配置中 feishu 有两条：ou_group_123 精确 + 通配
            // resolveModel 遍历时，先匹配到的生效
            // 由于配置顺序是精确在前，通配在后，应该匹配到精确的
            String model = config.resolveModel("feishu", "ou_group_123", null);
            assertEquals("doubao-pro-32k", model);
        }

        @Test
        @DisplayName("feishu + 其他 ID → deepseek-chat（通配）")
        void wildcardForOtherId() {
            String model = config.resolveModel("feishu", "different_id", null);
            assertEquals("deepseek-chat", model);
        }
    }

    // ========================================================================
    // equals / hashCode
    // ========================================================================

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("相同字段相等")
        void equalWhenSameFields() {
            ChannelOverride a = new ChannelOverride("feishu", "ou_1", "model", "url", "suffix");
            ChannelOverride b = new ChannelOverride("feishu", "ou_1", "model", "url", "suffix");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("不同 channel 不相等")
        void notEqualDifferentChannel() {
            ChannelOverride a = new ChannelOverride("feishu", null, "model", null, null);
            ChannelOverride b = new ChannelOverride("qqbot", null, "model", null, null);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("不同 channelId 不相等")
        void notEqualDifferentChannelId() {
            ChannelOverride a = new ChannelOverride("feishu", "ou_1", "model", null, null);
            ChannelOverride b = new ChannelOverride("feishu", "ou_2", "model", null, null);
            assertNotEquals(a, b);
        }
    }
}
