package com.nousresearch.hermes.gateway.platforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Telegram platform adapter.
 * Handles Telegram bot messages via polling.
 */
public class TelegramAdapter implements PlatformAdapter {
    private static final Logger logger = LoggerFactory.getLogger(TelegramAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_BASE = "https://api.telegram.org/bot";
    
    private final HermesConfig config;
    private final OkHttpClient httpClient;
    private AIAgent agent;
    private volatile boolean connected;
    private volatile boolean running;
    
    private String botToken;
    private String apiUrl;
    private long lastUpdateId = 0;
    private Thread pollingThread;
    
    public TelegramAdapter(HermesConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public String getName() {
        return "telegram";
    }
    
    @Override
    public void start() throws Exception {
        logger.info("Starting Telegram adapter...");
        
        botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        if (botToken == null || botToken.isEmpty()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN must be set");
        }
        
        apiUrl = API_BASE + botToken;
        
        // Test connection
        if (!testConnection()) {
            throw new IllegalStateException("Failed to connect to Telegram API");
        }
        
        running = true;
        connected = true;
        
        // Start polling thread
        pollingThread = new Thread(this::pollUpdates, "telegram-polling");
        pollingThread.setDaemon(true);
        pollingThread.start();
        
        logger.info("Telegram adapter started");
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Stopping Telegram adapter...");
        running = false;
        connected = false;
        
        if (pollingThread != null) {
            pollingThread.interrupt();
            pollingThread.join(5000);
        }
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public void sendMessage(String chatId, String message) throws Exception {
        Map<String, Object> requestBody = Map.of(
            "chat_id", chatId,
            "text", message,
            "parse_mode", "Markdown"
        );
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
            .url(apiUrl + "/sendMessage")
            .post(body)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                throw new IOException("Failed to send message: " + response.code() + " - " + errorBody);
            }
            
            logger.debug("Message sent to Telegram chat: {}", chatId);
        }
    }
    
    @Override
    public void setAgent(AIAgent agent) {
        this.agent = agent;
    }
    
    /**
     * Poll for updates from Telegram.
     */
    private void pollUpdates() {
        while (running) {
            try {
                String url = apiUrl + "/getUpdates?offset=" + (lastUpdateId + 1) + "&limit=100";
                
                Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        logger.error("Failed to get updates: {}", response.code());
                        Thread.sleep(5000);
                        continue;
                    }
                    
                    String body = response.body().string();
                    JsonNode result = mapper.readTree(body);
                    
                    if (!result.path("ok").asBoolean()) {
                        logger.error("Telegram API error: {}", result.path("description").asText());
                        Thread.sleep(5000);
                        continue;
                    }
                    
                    JsonNode updates = result.path("result");
                    for (JsonNode update : updates) {
                        long updateId = update.path("update_id").asLong();
                        if (updateId > lastUpdateId) {
                            lastUpdateId = updateId;
                            processUpdate(update);
                        }
                    }
                }
                
                // Small delay to avoid rate limiting
                Thread.sleep(100);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error polling updates: {}", e.getMessage(), e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    /**
     * Process a single update.
     */
    private void processUpdate(JsonNode update) {
        try {
            JsonNode message = update.path("message");
            if (message.isMissingNode()) {
                return;
            }
            
            String chatId = message.path("chat").path("id").asText();
            String text = message.path("text").asText();
            String from = message.path("from").path("username").asText();
            
            if (text == null || text.isEmpty()) {
                return;
            }
            
            logger.info("Received message from @{}: {}", from, text);
            
            // Handle commands
            if (text.startsWith("/")) {
                handleCommand(chatId, text, from);
                return;
            }
            
            // Process with agent
            if (agent != null) {
                // In real implementation, this would:
                // 1. Create conversation context
                // 2. Process through agent
                // 3. Send response
                
                // For now, echo with a note
                sendMessage(chatId, "Received: " + text + "\n\n(Agent processing not yet implemented)");
            }
            
        } catch (Exception e) {
            logger.error("Failed to process update: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Handle bot commands.
     */
    private void handleCommand(String chatId, String text, String from) {
        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        
        try {
            switch (command) {
                case "/start":
                    sendMessage(chatId, "👋 Hello! I'm Hermes Agent.\n\nSend me a message and I'll help you with your tasks.");
                    break;
                case "/help":
                    sendMessage(chatId, "Available commands:\n" +
                        "/start - Start the bot\n" +
                        "/help - Show this help\n" +
                        "/status - Check bot status\n" +
                        "/model - Show current model");
                    break;
                case "/status":
                    sendMessage(chatId, "✅ Bot is running\n🤖 Model: " + 
                        (config != null ? config.getCurrentModel() : "unknown"));
                    break;
                case "/model":
                    sendMessage(chatId, "Current model: " + 
                        (config != null ? config.getCurrentModel() : "unknown"));
                    break;
                default:
                    sendMessage(chatId, "Unknown command: " + command + "\nUse /help for available commands.");
            }
        } catch (Exception e) {
            logger.error("Failed to handle command: {}", e.getMessage());
        }
    }
    
    /**
     * Test connection to Telegram API.
     */
    private boolean testConnection() {
        try {
            Request request = new Request.Builder()
                .url(apiUrl + "/getMe")
                .get()
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return false;
                }
                
                String body = response.body().string();
                JsonNode result = mapper.readTree(body);
                
                if (result.path("ok").asBoolean()) {
                    JsonNode bot = result.path("result");
                    String botName = bot.path("username").asText();
                    logger.info("Connected to Telegram as @{}", botName);
                    return true;
                }
            }
        } catch (Exception e) {
            logger.error("Connection test failed: {}", e.getMessage());
        }
        return false;
    }
}