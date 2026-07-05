package com.nousresearch.hermes.tenant.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1-2: 会话级 /model 覆盖持久化测试
 *
 * <p>3 条安全断言（计划要求）：</p>
 * <ol>
 *   <li>重启 round-trip 不丢</li>
 *   <li>被篡改的 sessions.json 里出现 api_key 字段也不加载</li>
 *   <li>/new 后 override 归零</li>
 * </ol>
 */
class ModelOverridePersistenceTest {

    private final JsonSessionSerializer serializer = new JsonSessionSerializer();

    private SessionSerializer.SessionData createSession() {
        return new SessionSerializer.SessionData(
            "session-123", "tenant-456", "node-1",
            Instant.now(), Instant.now(), Map.of(),
            true, List.of()
        );
    }

    // ========================================================================
    // ModelOverride 值对象
    // ========================================================================

    @Nested
    @DisplayName("ModelOverride 值对象")
    class ModelOverrideValueObject {

        @Test
        @DisplayName("基本构造")
        void basicConstruction() {
            ModelOverride mo = new ModelOverride("gpt-4o", "openai", "https://api.openai.com/v1");
            assertEquals("gpt-4o", mo.getModel());
            assertEquals("openai", mo.getProvider());
            assertEquals("https://api.openai.com/v1", mo.getBaseUrl());
        }

        @Test
        @DisplayName("null model 抛 NPE")
        void nullModelThrows() {
            assertThrows(NullPointerException.class, () -> new ModelOverride(null, null, null));
        }

        @Test
        @DisplayName("空字符串 provider/baseUrl 转为 null")
        void emptyStringsBecomeNull() {
            ModelOverride mo = new ModelOverride("gpt-4o", "  ", "  ");
            assertNull(mo.getProvider());
            assertNull(mo.getBaseUrl());
        }

        @Test
        @DisplayName("toMap 不包含 api_key")
        void toMapNoApiKey() {
            ModelOverride mo = new ModelOverride("gpt-4o", "openai", "https://api.openai.com/v1");
            Map<String, Object> map = mo.toMap();
            assertFalse(map.containsKey("api_key"));
            assertFalse(map.containsKey("apikey"));
            assertFalse(map.containsKey("secret"));
            assertFalse(map.containsKey("token"));
        }

        @Test
        @DisplayName("toMap → fromMap round-trip")
        void roundTripMap() {
            ModelOverride original = new ModelOverride("doubao-pro", "volcengine", "https://ark.cn-beijing.volces.com/api/v3");
            Map<String, Object> map = original.toMap();
            ModelOverride restored = ModelOverride.fromMap(map);
            assertEquals(original, restored);
        }

        @Test
        @DisplayName("fromMap(null) 返回 null")
        void fromMapNull() {
            assertNull(ModelOverride.fromMap(null));
        }

        @Test
        @DisplayName("fromMap 空 map 返回 null")
        void fromMapEmpty() {
            assertNull(ModelOverride.fromMap(Map.of()));
        }
    }

    // ========================================================================
    // 安全断言 1: 重启 round-trip 不丢
    // ========================================================================

    @Nested
    @DisplayName("安全断言 1: 序列化 round-trip 不丢")
    class RoundTripTest {

        @Test
        @DisplayName("serialize → deserialize → getModelOverride 保持一致")
        void roundTripPreservesOverride() {
            SessionSerializer.SessionData original = createSession();
            ModelOverride override = new ModelOverride("deepseek-chat", "deepseek", "https://api.deepseek.com/v1");
            SessionSerializer.SessionData withOverride = JsonSessionSerializer.setModelOverride(original, override);

            byte[] bytes = serializer.serialize(withOverride);
            SessionSerializer.SessionData restored = serializer.deserialize(bytes);
            ModelOverride restoredOverride = JsonSessionSerializer.getModelOverride(restored);

            assertNotNull(restoredOverride);
            assertEquals(override.getModel(), restoredOverride.getModel());
            assertEquals(override.getProvider(), restoredOverride.getProvider());
            assertEquals(override.getBaseUrl(), restoredOverride.getBaseUrl());
        }

        @Test
        @DisplayName("无 override 的 session round-trip 仍为 null")
        void roundTripNoOverride() {
            SessionSerializer.SessionData original = createSession();
            byte[] bytes = serializer.serialize(original);
            SessionSerializer.SessionData restored = serializer.deserialize(bytes);
            assertNull(JsonSessionSerializer.getModelOverride(restored));
        }

        @Test
        @DisplayName("只存 model 不存 provider/baseUrl 的 round-trip")
        void roundTripModelOnly() {
            SessionSerializer.SessionData original = createSession();
            ModelOverride override = new ModelOverride("qwen-max", null, null);
            SessionSerializer.SessionData withOverride = JsonSessionSerializer.setModelOverride(original, override);

            byte[] bytes = serializer.serialize(withOverride);
            SessionSerializer.SessionData restored = serializer.deserialize(bytes);
            ModelOverride restoredOverride = JsonSessionSerializer.getModelOverride(restored);

            assertNotNull(restoredOverride);
            assertEquals("qwen-max", restoredOverride.getModel());
            assertNull(restoredOverride.getProvider());
            assertNull(restoredOverride.getBaseUrl());
        }
    }

    // ========================================================================
    // 安全断言 2: 被篡改的 sessions.json 里出现 api_key 也不加载
    // ========================================================================

    @Nested
    @DisplayName("安全断言 2: api_key 被 sanitize 移除")
    class SanitizeTest {

        @Test
        @DisplayName("反序列化后 api_key 被移除")
        void apiKeyStrippedOnDeserialize() {
            // 模拟被篡改的 JSON：metadata.model_override 里混入了 api_key
            String tamperedJson = """
                {
                  "sessionId": "session-evil",
                  "tenantId": "tenant-1",
                  "nodeId": "node-1",
                  "createdAt": "2026-07-05T08:00:00Z",
                  "lastActivity": "2026-07-05T08:00:00Z",
                  "metadata": {
                    "model_override": {
                      "model": "gpt-4o",
                      "provider": "openai",
                      "base_url": "https://api.openai.com/v1",
                      "api_key": "sk-evil-key-12345"
                    }
                  },
                  "active": true,
                  "messages": []
                }
                """;

            SessionSerializer.SessionData restored = serializer.deserializeFromString(tamperedJson);
            assertNotNull(restored);

            ModelOverride override = JsonSessionSerializer.getModelOverride(restored);
            assertNotNull(override);
            assertEquals("gpt-4o", override.getModel());
            assertEquals("openai", override.getProvider());

            // api_key 必须被移除
            Object metadata = restored.metadata();
            @SuppressWarnings("unchecked")
            Map<String, Object> overrideMap = (Map<String, Object>) ((Map<String, Object>) metadata).get("model_override");
            assertFalse(overrideMap.containsKey("api_key"));
        }

        @Test
        @DisplayName("多种敏感字段全部被移除")
        void allForbiddenFieldsStripped() {
            String tamperedJson = """
                {
                  "sessionId": "session-evil2",
                  "tenantId": "tenant-1",
                  "nodeId": "node-1",
                  "createdAt": "2026-07-05T08:00:00Z",
                  "lastActivity": "2026-07-05T08:00:00Z",
                  "metadata": {
                    "model_override": {
                      "model": "claude-3",
                      "api_key": "sk-123",
                      "apikey": "sk-456",
                      "api-key": "sk-789",
                      "secret": "secret123",
                      "password": "pass123",
                      "token": "tok123",
                      "authorization": "Bearer xyz",
                      "auth_token": "auth123"
                    }
                  },
                  "active": true,
                  "messages": []
                }
                """;

            SessionSerializer.SessionData restored = serializer.deserializeFromString(tamperedJson);
            ModelOverride override = JsonSessionSerializer.getModelOverride(restored);

            // model 保留
            assertNotNull(override);
            assertEquals("claude-3", override.getModel());

            // 所有敏感字段移除
            @SuppressWarnings("unchecked")
            Map<String, Object> overrideMap = (Map<String, Object>) restored.metadata().get("model_override");
            for (String forbidden : List.of("api_key", "apikey", "api-key", "secret", "password", "token", "authorization", "auth_token")) {
                assertFalse(overrideMap.containsKey(forbidden),
                    "Forbidden field '" + forbidden + "' should have been stripped");
            }
        }

        @Test
        @DisplayName("getModelOverride 二次防御性 sanitize")
        void getModelOverrideAlsoSanitizes() {
            // 直接构造带 api_key 的 metadata（绕过 deserialize 的 sanitize）
            Map<String, Object> evilMetadata = new java.util.HashMap<>();
            Map<String, Object> evilOverride = new java.util.HashMap<>();
            evilOverride.put("model", "gpt-4o");
            evilOverride.put("api_key", "sk-evil");
            evilMetadata.put("model_override", evilOverride);

            SessionSerializer.SessionData evilData = new SessionSerializer.SessionData(
                "s1", "t1", "n1", Instant.now(), Instant.now(),
                evilMetadata, true, List.of()
            );

            // getModelOverride 应该二次清理
            ModelOverride override = JsonSessionSerializer.getModelOverride(evilData);
            assertNotNull(override);
            assertEquals("gpt-4o", override.getModel());
        }
    }

    // ========================================================================
    // 安全断言 3: /new 后 override 归零
    // ========================================================================

    @Nested
    @DisplayName("安全断言 3: /new 重置后 override 归零")
    class ClearOverrideTest {

        @Test
        @DisplayName("setModelOverride → clearModelOverride → null")
        void clearOverride() {
            SessionSerializer.SessionData session = createSession();
            ModelOverride override = new ModelOverride("gpt-4o", "openai", null);
            session = JsonSessionSerializer.setModelOverride(session, override);
            assertNotNull(JsonSessionSerializer.getModelOverride(session));

            // /new 重置
            session = JsonSessionSerializer.clearModelOverride(session);
            assertNull(JsonSessionSerializer.getModelOverride(session));
        }

        @Test
        @DisplayName("clearModelOverride 后序列化不含 model_override")
        void clearOverrideSerialized() {
            SessionSerializer.SessionData session = createSession();
            ModelOverride override = new ModelOverride("gpt-4o", "openai", null);
            session = JsonSessionSerializer.setModelOverride(session, override);

            session = JsonSessionSerializer.clearModelOverride(session);
            byte[] bytes = serializer.serialize(session);
            String json = new String(bytes);

            assertFalse(json.contains("model_override"));
            assertFalse(json.contains("gpt-4o"));
        }

        @Test
        @DisplayName("clearModelOverride(null) 不崩溃")
        void clearNullSafe() {
            assertNull(JsonSessionSerializer.clearModelOverride(null));
        }
    }

    // ========================================================================
    // setModelOverride / getModelOverride 存取
    // ========================================================================

    @Nested
    @DisplayName("setModelOverride / getModelOverride 存取")
    class SetGetOverride {

        @Test
        @DisplayName("set → get 一致")
        void setGetConsistency() {
            SessionSerializer.SessionData session = createSession();
            ModelOverride override = new ModelOverride("doubao-pro-32k", "volcengine", "https://ark.cn-beijing.volces.com/api/v3");
            SessionSerializer.SessionData withOverride = JsonSessionSerializer.setModelOverride(session, override);

            ModelOverride retrieved = JsonSessionSerializer.getModelOverride(withOverride);
            assertEquals(override, retrieved);
        }

        @Test
        @DisplayName("set 后原 session 不变（不可变 record）")
        void originalUnchanged() {
            SessionSerializer.SessionData session = createSession();
            ModelOverride override = new ModelOverride("gpt-4o", null, null);
            JsonSessionSerializer.setModelOverride(session, override);

            // 原 session 仍然没有 override
            assertNull(JsonSessionSerializer.getModelOverride(session));
        }

        @Test
        @DisplayName("set null 等同于 clear")
        void setNullEqualsClear() {
            SessionSerializer.SessionData session = createSession();
            ModelOverride override = new ModelOverride("gpt-4o", null, null);
            session = JsonSessionSerializer.setModelOverride(session, override);
            session = JsonSessionSerializer.setModelOverride(session, null);

            assertNull(JsonSessionSerializer.getModelOverride(session));
        }

        @Test
        @DisplayName("metadata 中其他字段不受影响")
        void otherMetadataPreserved() {
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("custom_key", "custom_value");
            metadata.put("other_data", 42);

            SessionSerializer.SessionData session = new SessionSerializer.SessionData(
                "s1", "t1", "n1", Instant.now(), Instant.now(),
                metadata, true, List.of()
            );

            ModelOverride override = new ModelOverride("gpt-4o", null, null);
            SessionSerializer.SessionData withOverride = JsonSessionSerializer.setModelOverride(session, override);

            // 原有 metadata 保留
            assertEquals("custom_value", withOverride.metadata().get("custom_key"));
            assertEquals(42, withOverride.metadata().get("other_data"));
            // 新增 model_override
            assertNotNull(withOverride.metadata().get("model_override"));
        }
    }
}
