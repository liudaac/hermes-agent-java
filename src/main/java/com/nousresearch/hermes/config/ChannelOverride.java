package com.nousresearch.hermes.config;

import java.util.Objects;

/**
 * S1-1: Per-Channel Model + System Prompt Override 值对象。
 *
 * <p>允许同一租户在不同 IM/工作区上绑不同模型 & prompt。
 * 配置示例（application-*.yaml）：</p>
 *
 * <pre>{@code
 * channel-overrides:
 *   - channel: feishu
 *     channel-id: "ou_xxx"      # 可选，不填则匹配所有 feishu 频道
 *     model: "doubao-pro-32k"
 *     base-url: "https://ark.cn-beijing.volces.com/api/v3"
 *     system-prompt-suffix: "你是一个飞书助手。"
 *   - channel: qqbot
 *     model: "deepseek-chat"
 *     base-url: "https://api.deepseek.com/v1"
 *   - channel: discord
 *     model: "gpt-4o"
 * }</pre>
 *
 * <p>优先级：session /model > channel override > tenant default > global</p>
 */
public class ChannelOverride {

    private final String channel;        // e.g. "feishu", "qqbot", "discord"
    private final String channelId;      // 可选：特定频道/群 ID，null 匹配所有
    private final String model;          // 模型名
    private final String baseUrl;        // 可选：自定义 base URL
    private final String systemPromptSuffix; // 可选：附加到 system prompt 的文本

    public ChannelOverride(String channel, String channelId, String model,
                           String baseUrl, String systemPromptSuffix) {
        this.channel = Objects.requireNonNull(channel, "channel cannot be null").toLowerCase();
        this.channelId = (channelId != null && !channelId.isBlank()) ? channelId : null;
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : null;
        this.systemPromptSuffix = systemPromptSuffix != null && !systemPromptSuffix.isBlank()
            ? systemPromptSuffix : null;
    }

    /**
     * 检查此 override 是否匹配给定的 channel 和 channelId。
     *
     * <p>匹配规则：</p>
     * <ol>
     *   <li>channel 必须相等（大小写不敏感）</li>
     *   <li>如果此 override 的 channelId 为 null，匹配所有该 channel 的消息</li>
     *   <li>如果此 override 的 channelId 非 null，必须精确匹配</li>
     * </ol>
     *
     * @param msgChannel 消息来源的 channel（如 "feishu"）
     * @param msgChannelId 消息来源的 channel ID（如群 ID），可为 null
     * @return true 如果匹配
     */
    public boolean matches(String msgChannel, String msgChannelId) {
        if (msgChannel == null) return false;
        if (!channel.equalsIgnoreCase(msgChannel)) return false;
        if (channelId == null) return true; // 通配
        if (msgChannelId == null) return false;
        return channelId.equals(msgChannelId);
    }

    // Getters
    public String getChannel() { return channel; }
    public String getChannelId() { return channelId; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }
    public String getSystemPromptSuffix() { return systemPromptSuffix; }

    @Override
    public String toString() {
        return "ChannelOverride{channel='" + channel + "'" +
            (channelId != null ? ", channelId='" + channelId + "'" : "") +
            ", model='" + model + "'" +
            (baseUrl != null ? ", baseUrl='" + baseUrl + "'" : "") +
            (systemPromptSuffix != null ? ", systemPromptSuffix='...'" : "") +
            "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChannelOverride that)) return false;
        return channel.equals(that.channel) &&
            Objects.equals(channelId, that.channelId) &&
            Objects.equals(model, that.model) &&
            Objects.equals(baseUrl, that.baseUrl) &&
            Objects.equals(systemPromptSuffix, that.systemPromptSuffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channel, channelId, model, baseUrl, systemPromptSuffix);
    }
}
