package com.nousresearch.hermes.agent.transports;

import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Base interface for all model transports.
 * Mirrors Python BaseTransport abstract class.
 */
public interface BaseTransport {

    /**
     * Get the transport type.
     */
    TransportType getType();

    /**
     * Send a chat request to the model.
     *
     * @param messages List of messages in the conversation
     * @param tools Available tools for the model
     * @param model Model identifier
     * @param options Additional options (temperature, max_tokens, etc.)
     * @return Transport response containing the model's reply
     */
    TransportResponse chat(
        List<TransportMessage> messages,
        List<ToolDefinition> tools,
        String model,
        Map<String, Object> options
    );

    /**
     * Stream a chat request to the model.
     *
     * @param messages List of messages in the conversation
     * @param tools Available tools for the model
     * @param model Model identifier
     * @param options Additional options
     * @return Stream of transport responses
     */
    java.util.stream.Stream<TransportResponse> chatStream(
        List<TransportMessage> messages,
        List<ToolDefinition> tools,
        String model,
        Map<String, Object> options
    );

    /**
     * Check if this transport supports the given model.
     */
    boolean supportsModel(String model);

    /**
     * Get provider-specific headers for requests.
     */
    Map<String, String> getHeaders();

    /**
     * Close any resources held by this transport.
     */
    default void close() {}

    /**
     * Represents a tool definition for transport.
     */
    record ToolDefinition(
        String name,
        String description,
        JSONObject parameters,
        boolean required
    ) {
        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("description", description);
            if (parameters != null) {
                json.put("parameters", parameters);
            }
            return json;
        }
    }
}
