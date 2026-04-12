package com.nousresearch.hermes.gateway.platforms;

import com.nousresearch.hermes.agent.AIAgent;

/**
 * Interface for messaging platform adapters.
 * Implementations handle platform-specific message receiving and sending.
 */
public interface PlatformAdapter {
    
    /**
     * Get the platform name.
     */
    String getName();
    
    /**
     * Start the adapter.
     */
    void start() throws Exception;
    
    /**
     * Stop the adapter.
     */
    void stop() throws Exception;
    
    /**
     * Check if connected.
     */
    boolean isConnected();
    
    /**
     * Send a message to a chat.
     * @param chatId The chat/channel ID
     * @param message The message content
     */
    void sendMessage(String chatId, String message) throws Exception;
    
    /**
     * Set the agent for handling messages.
     */
    void setAgent(AIAgent agent);
}
