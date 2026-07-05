package com.nousresearch.hermes.gateway;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.config.ModelRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1-3: per-client model_routes 测试
 *
 * <p>验证 ModelRoute 别名映射 + OpenAI 兼容 API + Redactor。</p>
 */
class ModelRoutesTest {

    private HermesConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new HermesConfig();

        Map<String, Object> cfgMap = new HashMap<>();

        // global model
        Map<String, Object> modelCfg = new HashMap<>();
        modelCfg.put("provider", "openrouter");
        modelCfg.put("model", "anthropic/claude-3.5-sonnet");
        cfgMap.put("model", modelCfg);

        // model_routes
        List<Map<String, Object>> routes = new ArrayList<>();

        Map<String, Object> r1 = new HashMap<>();
        r1.put("alias", "gpt-4");
        r1.put("model", "gpt-4o");
        r1.put("provider", "openai");
        r1.put("base-url", "https://api.openai.com/v1");
        routes.add(r1);

        Map<String, Object> r2 = new HashMap<>();
        r2.put("alias", "claude");
        r2.put("model", "anthropic/claude-3.5-sonnet");
        r2.put("provider", "openrouter");
        routes.add(r2);

        Map<String, Object> r3 = new HashMap<>();
        r3.put("alias", "doubao");
        r3.put("model", "doubao-pro-32k");
        r3.put("provider", "volcengine");
        r3.put("base-url", "https://ark.cn-beijing.volces.com/api/v3");
        routes.add(r3);

        cfgMap.put("model_routes", routes);

        java.lang.reflect.Field f = HermesConfig.class.getDeclaredField("config");
        f.setAccessible(true);
        f.set(config, cfgMap);
    }

    // ========================================================================
    // ModelRoute 值对象
    // ========================================================================

    @Nested
    @DisplayName("ModelRoute 值对象")
    class ModelRouteValueObject {

        @Test
        @DisplayName("基本构造")
        void basicConstruction() {
            ModelRoute r = new ModelRoute("gpt-4", "gpt-4o", "openai", "https://api.openai.com/v1");
            assertEquals("gpt-4", r.getAlias());
            assertEquals("gpt-4o", r.getModel());
            assertEquals("openai", r.getProvider());
            assertEquals("https://api.openai.com/v1", r.getBaseUrl());
        }

        @Test
        @DisplayName("alias 转小写")
        void aliasLowercased() {
            ModelRoute r = new ModelRoute("GPT-4", "gpt-4o", null, null);
            assertEquals("gpt-4", r.getAlias());
        }

        @Test
        @DisplayName("空 provider/baseUrl 转为 null")
        void emptyStringsBecomeNull() {
            ModelRoute r = new ModelRoute("gpt-4", "gpt-4o", "  ", "  ");
            assertNull(r.getProvider());
            assertNull(r.getBaseUrl());
        }

        @Test
        @DisplayName("null alias 抛 NPE")
        void nullAliasThrows() {
            assertThrows(NullPointerException.class, () -> new ModelRoute(null, "model", null, null));
        }

        @Test
        @DisplayName("null model 抛 NPE")
        void nullModelThrows() {
            assertThrows(NullPointerException.class, () -> new ModelRoute("alias", null, null, null));
        }
    }

    // ========================================================================
    // getModelRoutes 配置解析
    // ========================================================================

    @Nested
    @DisplayName("getModelRoutes 配置解析")
    class GetModelRoutes {

        @Test
        @DisplayName("应解析出 3 个 route")
        void correctCount() {
            assertEquals(3, config.getModelRoutes().size());
        }

        @Test
        @DisplayName("第一个 route 有完整字段")
        void firstRouteComplete() {
            ModelRoute r = config.getModelRoutes().get(0);
            assertEquals("gpt-4", r.getAlias());
            assertEquals("gpt-4o", r.getModel());
            assertEquals("openai", r.getProvider());
            assertEquals("https://api.openai.com/v1", r.getBaseUrl());
        }

        @Test
        @DisplayName("无 model_routes 配置 → 空列表")
        void noRoutesConfig() throws Exception {
            HermesConfig emptyConfig = new HermesConfig();
            assertTrue(emptyConfig.getModelRoutes().isEmpty());
        }
    }

    // ========================================================================
    // resolveModelRoute 别名解析
    // ========================================================================

    @Nested
    @DisplayName("resolveModelRoute 别名解析")
    class ResolveModelRoute {

        @Test
        @DisplayName("gpt-4 别名 → gpt-4o")
        void gpt4Alias() {
            ModelRoute route = config.resolveModelRoute("gpt-4", null);
            assertNotNull(route);
            assertEquals("gpt-4o", route.getModel());
            assertEquals("openai", route.getProvider());
        }

        @Test
        @DisplayName("claude 别名 → anthropic/claude-3.5-sonnet")
        void claudeAlias() {
            ModelRoute route = config.resolveModelRoute("claude", null);
            assertNotNull(route);
            assertEquals("anthropic/claude-3.5-sonnet", route.getModel());
        }

        @Test
        @DisplayName("doubao 别名 → doubao-pro-32k + volcengine base URL")
        void doubaoAlias() {
            ModelRoute route = config.resolveModelRoute("doubao", null);
            assertNotNull(route);
            assertEquals("doubao-pro-32k", route.getModel());
            assertEquals("volcengine", route.getProvider());
            assertEquals("https://ark.cn-beijing.volces.com/api/v3", route.getBaseUrl());
        }

        @Test
        @DisplayName("大小写不敏感：GPT-4 → gpt-4o")
        void caseInsensitive() {
            ModelRoute route = config.resolveModelRoute("GPT-4", null);
            assertNotNull(route);
            assertEquals("gpt-4o", route.getModel());
        }

        @Test
        @DisplayName("未知别名 → null（用 global default）")
        void unknownAlias() {
            assertNull(config.resolveModelRoute("unknown-model", null));
        }

        @Test
        @DisplayName("null alias → null")
        void nullAlias() {
            assertNull(config.resolveModelRoute(null, null));
        }

        @Test
        @DisplayName("session /model 最高优先（直接用值）")
        void sessionOverrideDirect() {
            ModelRoute route = config.resolveModelRoute("gpt-4", "custom-model");
            assertNotNull(route);
            assertEquals("custom-model", route.getModel());
        }

        @Test
        @DisplayName("session /model 是别名 → 解析别名")
        void sessionOverrideIsAlias() {
            ModelRoute route = config.resolveModelRoute("gpt-4", "doubao");
            assertNotNull(route);
            assertEquals("doubao-pro-32k", route.getModel());
        }
    }

    // ========================================================================
    // Redactor — api_key 绝不写日志
    // ========================================================================

    @Nested
    @DisplayName("Redactor — api_key 绝不写日志")
    class RedactorTest {

        @Test
        @DisplayName("api_key=xxx 被移除")
        void apiKeyRedacted() {
            String input = "Error with api_key=sk-1234567890abcdef";
            String result = OpenAICompatHandler.redactLog(input);
            assertTrue(result.contains("***REDACTED***"));
            assertFalse(result.contains("sk-1234567890abcdef"));
        }

        @Test
        @DisplayName("api-key=xxx 被移除")
        void apiKeyHyphenRedacted() {
            String input = "config api-key=sk-abcdef123456";
            String result = OpenAICompatHandler.redactLog(input);
            assertTrue(result.contains("***REDACTED***"));
            assertFalse(result.contains("sk-abcdef123456"));
        }

        @Test
        @DisplayName("apikey=xxx 被移除")
        void apikeyNoUnderscoreRedacted() {
            String input = "set apikey=mysecretkey";
            String result = OpenAICompatHandler.redactLog(input);
            assertTrue(result.contains("***REDACTED***"));
            assertFalse(result.contains("mysecretkey"));
        }

        @Test
        @DisplayName("Authorization header 被移除")
        void authHeaderRedacted() {
            String input = "Authorization=Bearer abc123";
            String result = OpenAICompatHandler.redactLog(input);
            assertTrue(result.contains("***REDACTED***"));
            assertFalse(result.contains("abc123"));
        }

        @Test
        @DisplayName("sk- 开头的长 token 被移除")
        void skTokenRedacted() {
            String input = "Using key sk-proj-abcdefghijklmnopqrstuvwxyz1234567890";
            String result = OpenAICompatHandler.redactLog(input);
            assertTrue(result.contains("***REDACTED***"));
            assertFalse(result.contains("sk-proj-abcdefghijklmnopqrstuvwxyz1234567890"));
        }

        @Test
        @DisplayName("Bearer token 被移除")
        void bearerTokenRedacted() {
            String input = "header: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
            String result = OpenAICompatHandler.redactLog(input);
            assertTrue(result.contains("***REDACTED***"));
            assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        }

        @Test
        @DisplayName("正常日志不受影响")
        void normalLogUnchanged() {
            String input = "Request model=gpt-4o, duration=1234ms";
            String result = OpenAICompatHandler.redactLog(input);
            assertEquals(input, result);
        }

        @Test
        @DisplayName("null 输入返回 null")
        void nullInput() {
            assertNull(OpenAICompatHandler.redactLog(null));
        }

        @Test
        @DisplayName("混合敏感信息全部移除")
        void mixedSensitiveAllRedacted() {
            String input = "api_key=sk-12345 auth_token=token123 Authorization=Bearer xyz";
            String result = OpenAICompatHandler.redactLog(input);
            // 统计 ***REDACTED*** 出现次数
            long count = result.split("\\*\\*\\*REDACTED\\*\\*\\*").length - 1;
            assertTrue(count >= 2, "Should have at least 2 redactions, got: " + result);
            assertFalse(result.contains("sk-12345"));
            assertFalse(result.contains("token123"));
        }
    }
}
