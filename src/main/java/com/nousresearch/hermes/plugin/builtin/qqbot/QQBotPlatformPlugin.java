package com.nousresearch.hermes.plugin.builtin.qqbot;

import com.nousresearch.hermes.plugin.Plugin;
import com.nousresearch.hermes.plugin.context.PluginContext;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;
import com.nousresearch.hermes.gateway.platforms.qqbot.QQBotAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in QQBot platform adapter plugin.
 */
public class QQBotPlatformPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(QQBotPlatformPlugin.class);

    @Override
    public void register(PluginContext ctx) {
        logger.info("Registering QQBot platform adapter");

        ctx.registerPlatform(PlatformEntry.builder("qqbot", "QQ Bot")
                .adapterFactory(config -> new QQBotAdapter())
                .checkFn(() -> System.getenv("QQBOT_APP_ID") != null || System.getenv("QQ_APP_ID") != null)
                .validateConfig(config -> true)
                .requiredEnv(java.util.List.of("QQBOT_APP_ID", "QQBOT_SECRET"))
                .installHint("Set QQBOT_APP_ID and QQBOT_SECRET environment variables")
                .source("builtin")
                .pluginName(ctx.getPluginName())
                .emoji("🐧")
                .platformHint("You are on QQ. Keep replies concise and friendly.")
                .cronDeliverEnvVar("QQBOT_HOME_CHANNEL")
                .build());
    }
}
