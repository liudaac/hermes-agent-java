package com.nousresearch.hermes.plugin.builtin.feishu;

import com.nousresearch.hermes.plugin.Plugin;
import com.nousresearch.hermes.plugin.context.PluginContext;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;
import com.nousresearch.hermes.gateway.platforms.FeishuAdapterV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in Feishu platform adapter plugin.
 * Auto-loaded because it's a bundled platform plugin.
 */
public class FeishuPlatformPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(FeishuPlatformPlugin.class);

    @Override
    public void register(PluginContext ctx) {
        logger.info("Registering Feishu platform adapter");

        ctx.registerPlatform(PlatformEntry.builder("feishu", "Feishu")
                .adapterFactory(config -> new FeishuAdapterV2())
                .checkFn(() -> System.getenv("FEISHU_APP_ID") != null)
                .validateConfig(config -> true)
                .requiredEnv(java.util.List.of("FEISHU_APP_ID", "FEISHU_APP_SECRET"))
                .installHint("Set FEISHU_APP_ID and FEISHU_APP_SECRET environment variables")
                .source("builtin")
                .pluginName(ctx.getPluginName())
                .emoji("📋")
                .platformHint("You are on Feishu (Lark). Use concise replies. Markdown is supported.")
                .cronDeliverEnvVar("FEISHU_HOME_CHAT")
                .build());
    }
}
