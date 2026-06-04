package com.nousresearch.hermes.plugin.builtin.feishu;

import com.nousresearch.hermes.plugin.Plugin;
import com.nousresearch.hermes.plugin.context.PluginContext;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;
import com.nousresearch.hermes.gateway.platforms.FeishuAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in Feishu V1 (legacy) platform adapter plugin.
 */
public class FeishuLegacyPlatformPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(FeishuLegacyPlatformPlugin.class);

    @Override
    public void register(PluginContext ctx) {
        logger.info("Registering Feishu V1 (legacy) platform adapter");

        ctx.registerPlatform(PlatformEntry.builder("feishu-legacy", "Feishu (Legacy)")
                .adapterFactory(config -> {
                    if (config instanceof com.nousresearch.hermes.config.HermesConfig hc) {
                        return new FeishuAdapter(hc);
                    }
                    return new FeishuAdapter(new com.nousresearch.hermes.config.HermesConfig());
                })
                .checkFn(() -> System.getenv("FEISHU_APP_ID") != null)
                .validateConfig(config -> true)
                .requiredEnv(java.util.List.of("FEISHU_APP_ID", "FEISHU_APP_SECRET"))
                .installHint("Set FEISHU_APP_ID and FEISHU_APP_SECRET environment variables")
                .source("builtin")
                .pluginName(ctx.getPluginName())
                .emoji("📋")
                .platformHint("You are on Feishu (Legacy V1 adapter). Use concise replies.")
                .cronDeliverEnvVar("FEISHU_HOME_CHAT")
                .build());
    }
}
