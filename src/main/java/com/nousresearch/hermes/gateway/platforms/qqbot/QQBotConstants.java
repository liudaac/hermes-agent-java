package com.nousresearch.hermes.gateway.platforms.qqbot;

/**
 * Constants for QQ Bot integration.
 * Mirrors Python gateway/platforms/qqbot/constants.py
 */
public class QQBotConstants {
    
    // API Endpoints
    public static final String API_BASE_URL = "https://api.sgroup.qq.com";
    public static final String SANDBOX_API_URL = "https://sandbox.api.sgroup.qq.com";
    
    // Gateway
    public static final String GATEWAY_URL = API_BASE_URL + "/gateway";
    public static final String GATEWAY_BOT_URL = API_BASE_URL + "/gateway/bot";
    
    // Message Types
    public static final int MSG_TYPE_TEXT = 0;
    public static final int MSG_TYPE_IMAGE = 1;
    public static final int MSG_TYPE_VIDEO = 2;
    public static final int MSG_TYPE_FILE = 3;
    public static final int MSG_TYPE_VOICE = 4;
    public static final int MSG_TYPE_MARKDOWN = 5;
    public static final int MSG_TYPE_ARK = 6;
    public static final int MSG_TYPE_EMBED = 7;
    public static final int MSG_TYPE_MEDIA = 8;
    
    // Event Types
    public static final String EVENT_AT_MESSAGE_CREATE = "AT_MESSAGE_CREATE";
    public static final String EVENT_DIRECT_MESSAGE_CREATE = "DIRECT_MESSAGE_CREATE";
    public static final String EVENT_GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE";
    public static final String EVENT_C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE";
    public static final String EVENT_MESSAGE_CREATE = "MESSAGE_CREATE";
    public static final String EVENT_GUILD_MEMBER_ADD = "GUILD_MEMBER_ADD";
    public static final String EVENT_GUILD_MEMBER_REMOVE = "GUILD_MEMBER_REMOVE";
    public static final String EVENT_CHANNEL_CREATE = "CHANNEL_CREATE";
    public static final String EVENT_CHANNEL_DELETE = "CHANNEL_DELETE";
    public static final String EVENT_READY = "READY";
    public static final String EVENT_RESUMED = "RESUMED";
    
    // Op Codes
    public static final int OP_DISPATCH = 0;
    public static final int OP_HEARTBEAT = 1;
    public static final int OP_IDENTIFY = 2;
    public static final int OP_RESUME = 6;
    public static final int OP_RECONNECT = 7;
    public static final int OP_INVALID_SESSION = 9;
    public static final int OP_HELLO = 10;
    public static final int OP_HEARTBEAT_ACK = 11;
    public static final int OP_HTTP_CALLBACK_ACK = 12;
    
    // Intents
    public static final int INTENT_GUILDS = 1 << 0;
    public static final int INTENT_GUILD_MEMBERS = 1 << 1;
    public static final int INTENT_GUILD_MESSAGES = 1 << 9;
    public static final int INTENT_GUILD_MESSAGE_REACTIONS = 1 << 10;
    public static final int INTENT_DIRECT_MESSAGE = 1 << 12;
    public static final int INTENT_OPEN_FORUMS_EVENT = 1 << 18;
    public static final int INTENT_AUDIO_OR_LIVE_CHANNEL_MEMBER = 1 << 19;
    public static final int INTENT_C2C_MESSAGE = 1 << 25;
    public static final int INTENT_GROUP_MESSAGE = 1 << 26;
    public static final int INTENT_INTERACTION = 1 << 26;
    public static final int INTENT_MESSAGE_AUDIT = 1 << 27;
    public static final int INTENT_FORUMS_EVENT = 1 << 28;
    public static final int INTENT_AUDIO_ACTION = 1 << 29;
    public static final int INTENT_AT_MESSAGES = 1 << 30;
    
    // Default intents for bot
    public static final int DEFAULT_INTENTS = 
        INTENT_AT_MESSAGES | 
        INTENT_DIRECT_MESSAGE | 
        INTENT_GUILD_MESSAGES |
        INTENT_C2C_MESSAGE |
        INTENT_GROUP_MESSAGE;
    
    // HTTP Headers
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_X_SIGNATURE = "X-Signature-Ed25519";
    public static final String HEADER_X_TIMESTAMP = "X-Signature-Timestamp";
    
    // Auth Types
    public static final String AUTH_TYPE_BOT = "Bot";
    public static final String AUTH_TYPE_BEARER = "Bearer";
    
    // Rate Limits
    public static final int RATE_LIMIT_MESSAGES_PER_SECOND = 5;
    public static final int RATE_LIMIT_MESSAGES_PER_MINUTE = 120;
    
    // Message Limits
    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_EMBED_TITLE_LENGTH = 256;
    public static final int MAX_EMBED_DESCRIPTION_LENGTH = 4096;
    public static final int MAX_EMBED_FIELDS = 25;
    public static final int MAX_EMBED_FIELD_NAME_LENGTH = 256;
    public static final int MAX_EMBED_FIELD_VALUE_LENGTH = 1024;
    
    private QQBotConstants() {
        // Prevent instantiation
    }
}
