package com.nousresearch.hermes.plugin.builtin.discord;

import com.nousresearch.hermes.plugin.Plugin;
import com.nousresearch.hermes.plugin.context.PluginContext;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;
import com.nousresearch.hermes.gateway.platforms.DiscordAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in Discord platform adapter plugin.
 */
public class DiscordPlatformPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(DiscordPlatformPlugin.class);

    @Override
    public void register(PluginContext ctx) {
        logger.info("Registering Discord platform adapter");

        ctx.registerPlatform(PlatformEntry.builder("discord", "Discord")
                .adapterFactory(config -> new DiscordAdapter())
                .checkFn(() -> System.getenv("DISCORD_BOT_TOKEN") != null)
                .validateConfig(config -> true)
                .requiredEnv(java.util.List.of("DISCORD_BOT_TOKEN"))
                .installHint("Set DISCORD_BOT_TOKEN environment variable (from Discord Developer Portal)")
                .source("builtin")
                .pluginName(ctx.getPluginName())
                .emoji("💬")
                .platformHint("You are on Discord. Do not use markdown code blocks in replies.")
                .cronDeliverEnvVar("DISCORD_HOME_CHANNEL")
                .build());
    }
}
