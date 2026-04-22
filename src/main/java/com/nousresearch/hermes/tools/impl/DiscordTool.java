package com.nousresearch.hermes.tools.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Discord server introspection and management tool.
 * Mirrors Python tools/discord_tool.py
 *
 * Provides Discord REST API interaction for Discord gateway.
 */
public class DiscordTool {
    private static final Logger logger = LoggerFactory.getLogger(DiscordTool.class);

    private static final String DISCORD_API_BASE = "https://discord.com/api/v10";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String botToken;

    public DiscordTool() {
        this.botToken = System.getenv("DISCORD_BOT_TOKEN");
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Check if Discord bot is configured.
     *
     * @return true if token is available
     */
    public boolean isAvailable() {
        return botToken != null && !botToken.isEmpty();
    }

    /**
     * Get current user (bot) info.
     *
     * @return User info
     */
    public JSONObject getCurrentUser() throws IOException {
        return get("/users/@me");
    }

    /**
     * Get guild (server) info.
     *
     * @param guildId Guild ID
     * @return Guild info
     */
    public JSONObject getGuild(String guildId) throws IOException {
        return get("/guilds/" + guildId);
    }

    /**
     * List guild channels.
     *
     * @param guildId Guild ID
     * @return Channels array
     */
    public JSONArray listGuildChannels(String guildId) throws IOException {
        String response = getRaw("/guilds/" + guildId + "/channels");
        return JSON.parseArray(response);
    }

    /**
     * Get channel info.
     *
     * @param channelId Channel ID
     * @return Channel info
     */
    public JSONObject getChannel(String channelId) throws IOException {
        return get("/channels/" + channelId);
    }

    /**
     * Get channel messages.
     *
     * @param channelId Channel ID
     * @param limit Number of messages (max 100)
     * @return Messages array
     */
    public JSONArray getChannelMessages(String channelId, int limit) throws IOException {
        String path = "/channels/" + channelId + "/messages?limit=" + Math.min(limit, 100);
        String response = getRaw(path);
        return JSON.parseArray(response);
    }

    /**
     * Send a message to a channel.
     *
     * @param channelId Channel ID
     * @param content Message content
     * @return Sent message
     */
    public JSONObject sendMessage(String channelId, String content) throws IOException {
        JSONObject body = new JSONObject();
        body.put("content", content);
        return post("/channels/" + channelId + "/messages", body);
    }

    /**
     * Send an embed message.
     *
     * @param channelId Channel ID
     * @param title Embed title
     * @param description Embed description
     * @param color Embed color
     * @return Sent message
     */
    public JSONObject sendEmbed(String channelId, String title, String description, int color) throws IOException {
        JSONObject body = new JSONObject();

        JSONObject embed = new JSONObject();
        embed.put("title", title);
        embed.put("description", description);
        embed.put("color", color);

        JSONArray embeds = new JSONArray();
        embeds.add(embed);
        body.put("embeds", embeds);

        return post("/channels/" + channelId + "/messages", body);
    }

    /**
     * Get guild members.
     *
     * @param guildId Guild ID
     * @param limit Number of members (max 1000)
     * @return Members array
     */
    public JSONArray getGuildMembers(String guildId, int limit) throws IOException {
        String path = "/guilds/" + guildId + "/members?limit=" + Math.min(limit, 1000);
        String response = getRaw(path);
        return JSON.parseArray(response);
    }

    /**
     * Search for guild members.
     *
     * @param guildId Guild ID
     * @param query Search query
     * @param limit Number of results
     * @return Members array
     */
    public JSONArray searchGuildMembers(String guildId, String query, int limit) throws IOException {
        String path = "/guilds/" + guildId + "/members/search?query=" + query + "&limit=" + Math.min(limit, 100);
        String response = getRaw(path);
        return JSON.parseArray(response);
    }

    /**
     * Get member info.
     *
     * @param guildId Guild ID
     * @param userId User ID
     * @return Member info
     */
    public JSONObject getMember(String guildId, String userId) throws IOException {
        return get("/guilds/" + guildId + "/members/" + userId);
    }

    /**
     * Create a DM channel.
     *
     * @param userId User ID
     * @return DM channel
     */
    public JSONObject createDM(String userId) throws IOException {
        JSONObject body = new JSONObject();
        body.put("recipient_id", userId);
        return post("/users/@me/channels", body);
    }

    // Private HTTP helpers

    private String getRaw(String path) throws IOException {
        if (!isAvailable()) {
            throw new IllegalStateException("DISCORD_BOT_TOKEN not set");
        }

        Request request = new Request.Builder()
            .url(DISCORD_API_BASE + path)
            .get()
            .header("Authorization", "Bot " + botToken)
            .header("User-Agent", "HermesJava/1.0")
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                logger.error("Discord API error {}: {}", response.code(), body);
                throw new IOException("Discord API error " + response.code() + ": " + body);
            }

            return body;
        }
    }

    private JSONObject get(String path) throws IOException {
        return JSON.parseObject(getRaw(path));
    }

    private JSONObject post(String path, JSONObject body) throws IOException {
        if (!isAvailable()) {
            throw new IllegalStateException("DISCORD_BOT_TOKEN not set");
        }

        Request request = new Request.Builder()
            .url(DISCORD_API_BASE + path)
            .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
            .header("Authorization", "Bot " + botToken)
            .header("Content-Type", "application/json")
            .header("User-Agent", "HermesJava/1.0")
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                logger.error("Discord API error {}: {}", response.code(), responseBody);
                throw new IOException("Discord API error " + response.code() + ": " + responseBody);
            }

            return JSON.parseObject(responseBody);
        }
    }
}
