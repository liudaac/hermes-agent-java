package com.nousresearch.hermes.plugin.builtin.telegram;

import com.nousresearch.hermes.plugin.Plugin;
import com.nousresearch.hermes.plugin.context.PluginContext;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;
import com.nousresearch.hermes.gateway.platforms.TelegramAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in Telegram platform adapter plugin.
 */
public class TelegramPlatformPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(TelegramPlatformPlugin.class);

    @Override
    public void register(PluginContext ctx) {
        logger.info("Registering Telegram platform adapter");

        ctx.registerPlatform(PlatformEntry.builder("telegram", "Telegram")
                .adapterFactory(config -> new TelegramAdapter())
                .checkFn(() -> System.getenv("TELEGRAM_BOT_TOKEN") != null)
                .validateConfig(config -> true)
                .requiredEnv(java.util.List.of("TELEGRAM_BOT_TOKEN"))
                .installHint("Set TELEGRAM_BOT_TOKEN environment variable (get from @BotFather)")
                .source("builtin")
                .pluginName(ctx.getPluginName())
                .emoji("✈️")
                .platformHint("You are on Telegram. Markdown is supported. Be concise.")
                .cronDeliverEnvVar("TELEGRAM_HOME_CHAT")
                .build());
    }
}
