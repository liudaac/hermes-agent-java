package com.nousresearch.hermes.gateway.platforms.feishu;

import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.gateway.IncomingMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeishuCommentAdapterTest {

    private static class SecureAdapter extends FeishuCommentAdapter {
        private final String token;
        private final String encryptKey;

        private SecureAdapter(String token, String encryptKey) {
            this.token = token;
            this.encryptKey = encryptKey;
        }

        @Override
        protected String getExpectedVerificationToken() {
            return token;
        }

        @Override
        protected String getEncryptKey() {
            return encryptKey;
        }
    }

    @Test
    @DisplayName("parseWebhook should convert Feishu comment events into gateway messages")
    void parseWebhookConvertsCommentEvent() {
        FeishuCommentAdapter adapter = new FeishuCommentAdapter();
        JSONObject payload = JSONObject.parseObject("""
            {
              "header": {"event_type": "comment.created"},
              "event": {
                "comment": {
                  "comment_id": "comment-123",
                  "content": "请帮我总结这段内容",
                  "creator": {"open_id": "ou_test"}
                },
                "document": {
                  "type": "docx",
                  "token": "doc-token-1"
                }
              }
            }
            """);

        IncomingMessage message = adapter.parseWebhook(payload);

        assertNotNull(message);
        assertEquals("comment-123", message.id());
        assertEquals("docx:doc-token-1:comment-123", message.channel());
        assertEquals("ou_test", message.sender());
        assertTrue(message.content().contains("Document: doc-token-1 (docx)"));
        assertTrue(message.content().contains("Comment by: ou_test"));
        assertTrue(message.content().contains("请帮我总结这段内容"));
        assertFalse(message.isGroup());
    }

    @Test
    @DisplayName("challenge response should require the configured verification token")
    void challengeResponseRequiresVerificationToken() {
        FeishuCommentAdapter adapter = new SecureAdapter("expected-token", null);

        JSONObject valid = JSONObject.parseObject("""
            {"token": "expected-token", "challenge": "challenge-value"}
            """);
        assertEquals("challenge-value", adapter.getWebhookChallengeResponse(valid).getString("challenge"));

        JSONObject invalid = JSONObject.parseObject("""
            {"token": "wrong-token", "challenge": "challenge-value"}
            """);
        assertNull(adapter.getWebhookChallengeResponse(invalid));
        assertFalse(adapter.verifyWebhook(invalid, Map.of(), invalid.toJSONString()));
    }

    @Test
    @DisplayName("verifyWebhook should reject stale timestamps")
    void verifyWebhookRejectsStaleTimestamp() {
        FeishuCommentAdapter adapter = new SecureAdapter("expected-token", null);
        JSONObject payload = JSONObject.parseObject("""
            {"token": "expected-token", "event": {}}
            """);

        assertFalse(adapter.verifyWebhook(
            payload,
            Map.of("X-Lark-Request-Timestamp", "1"),
            payload.toJSONString()
        ));
    }

    @Test
    @DisplayName("verifyWebhook should validate Feishu signature when encrypt key is configured")
    void verifyWebhookValidatesSignature() throws Exception {
        String encryptKey = "encrypt-key";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = "nonce-1";
        String rawBody = "{\"token\":\"expected-token\",\"event\":{}}";
        String signature = sha256Hex(timestamp + nonce + encryptKey + rawBody);

        FeishuCommentAdapter adapter = new SecureAdapter("expected-token", encryptKey);

        assertTrue(adapter.verifyWebhook(
            JSONObject.parseObject(rawBody),
            Map.of(
                "X-Lark-Request-Timestamp", timestamp,
                "X-Lark-Request-Nonce", nonce,
                "X-Lark-Signature", signature
            ),
            rawBody
        ));

        assertFalse(adapter.verifyWebhook(
            JSONObject.parseObject(rawBody),
            Map.of(
                "X-Lark-Request-Timestamp", timestamp,
                "X-Lark-Request-Nonce", nonce,
                "X-Lark-Signature", "bad-signature"
            ),
            rawBody
        ));
    }

    @Test
    @DisplayName("parseWebhook should ignore non-comment events")
    void parseWebhookIgnoresNonCommentEvents() {
        FeishuCommentAdapter adapter = new FeishuCommentAdapter();
        JSONObject payload = JSONObject.parseObject("""
            {
              "header": {"event_type": "im.message.receive_v1"},
              "event": {}
            }
            """);

        assertNull(adapter.parseWebhook(payload));
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
