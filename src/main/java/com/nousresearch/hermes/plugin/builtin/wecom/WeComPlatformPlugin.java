package com.nousresearch.hermes.plugin.builtin.wecom;

import com.nousresearch.hermes.plugin.Plugin;
import com.nousresearch.hermes.plugin.context.PluginContext;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;
import com.nousresearch.hermes.gateway.platforms.wecom.WeComCallbackAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in WeCom (Enterprise WeChat) platform adapter plugin.
 */
public class WeComPlatformPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(WeComPlatformPlugin.class);

    @Override
    public void register(PluginContext ctx) {
        logger.info("Registering WeCom platform adapter");

        ctx.registerPlatform(PlatformEntry.builder("wecom", "WeCom")
                .adapterFactory(config -> new WeComCallbackAdapter())
                .checkFn(() -> System.getenv("WECOM_CORP_ID") != null)
                .validateConfig(config -> true)
                .requiredEnv(java.util.List.of("WECOM_CORP_ID", "WECOM_SECRET", "WECOM_AGENT_ID"))
                .installHint("Set WECOM_CORP_ID, WECOM_SECRET, and WECOM_AGENT_ID environment variables")
                .source("builtin")
                .pluginName(ctx.getPluginName())
                .emoji("💼")
                .platformHint("You are on WeCom (Enterprise WeChat). Be professional and concise.")
                .cronDeliverEnvVar("WECOM_HOME_CHANNEL")
                .build());
    }
}
