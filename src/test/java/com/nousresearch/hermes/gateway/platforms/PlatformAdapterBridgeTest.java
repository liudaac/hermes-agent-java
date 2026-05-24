package com.nousresearch.hermes.gateway.platforms;

import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.gateway.IncomingMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformAdapterBridgeTest {

    private static class LegacyLifecycleAdapter implements PlatformAdapter {
        private String lastChatId;
        private String lastMessage;

        @Override
        public String getName() {
            return "legacy";
        }

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void stop() {
            // no-op
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendMessage(String chatId, String message) {
            this.lastChatId = chatId;
            this.lastMessage = message;
        }

        @Override
        public void setAgent(AIAgent agent) {
            // no-op
        }
    }

    @Test
    @DisplayName("Legacy lifecycle adapters should bridge to the gateway PlatformAdapter contract")
    void legacyAdapterBridgesToGatewayContract() throws Exception {
        LegacyLifecycleAdapter adapter = new LegacyLifecycleAdapter();

        assertTrue(adapter instanceof com.nousresearch.hermes.gateway.PlatformAdapter);
        assertEquals("legacy", adapter.getPlatformName());
        assertNull(adapter.parseWebhook(null));

        adapter.sendReply("chat-1", "msg-1", "hello");
        assertEquals("chat-1", adapter.lastChatId);
        assertEquals("hello", adapter.lastMessage);
    }
}
