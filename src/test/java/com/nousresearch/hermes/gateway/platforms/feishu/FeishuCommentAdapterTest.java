package com.nousresearch.hermes.gateway.platforms.feishu;

import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.gateway.IncomingMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeishuCommentAdapterTest {

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
}
