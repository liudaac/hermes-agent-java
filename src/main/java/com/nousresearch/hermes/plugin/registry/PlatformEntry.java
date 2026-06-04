package com.nousresearch.hermes.plugin.registry;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Metadata and factory for a single platform adapter.
 * Mirrors Python PlatformEntry dataclass.
 */
public class PlatformEntry {
    private final String name;
    private final String label;
    private final Function<Object, Object> adapterFactory;
    private final Supplier<Boolean> checkFn;
    private final Predicate<Object> validateConfig;
    private final List<String> requiredEnv;
    private final String installHint;
    private final String source; // "builtin" or "plugin"
    private final String pluginName;
    private final String allowedUsersEnv;
    private final String allowAllEnv;
    private final int maxMessageLength;
    private final boolean piiSafe;
    private final String emoji;
    private final boolean allowUpdateCommand;
    private final String platformHint;
    private final String cronDeliverEnvVar;

    public PlatformEntry(String name, String label,
                         Function<Object, Object> adapterFactory,
                         Supplier<Boolean> checkFn,
                         Predicate<Object> validateConfig,
                         List<String> requiredEnv,
                         String installHint,
                         String source,
                         String pluginName,
                         String allowedUsersEnv,
                         String allowAllEnv,
                         int maxMessageLength,
                         boolean piiSafe,
                         String emoji,
                         boolean allowUpdateCommand,
                         String platformHint,
                         String cronDeliverEnvVar) {
        this.name = name;
        this.label = label;
        this.adapterFactory = adapterFactory;
        this.checkFn = checkFn;
        this.validateConfig = validateConfig;
        this.requiredEnv = requiredEnv != null ? requiredEnv : List.of();
        this.installHint = installHint != null ? installHint : "";
        this.source = source != null ? source : "plugin";
        this.pluginName = pluginName != null ? pluginName : "";
        this.allowedUsersEnv = allowedUsersEnv != null ? allowedUsersEnv : "";
        this.allowAllEnv = allowAllEnv != null ? allowAllEnv : "";
        this.maxMessageLength = maxMessageLength;
        this.piiSafe = piiSafe;
        this.emoji = emoji != null ? emoji : "🔌";
        this.allowUpdateCommand = allowUpdateCommand;
        this.platformHint = platformHint != null ? platformHint : "";
        this.cronDeliverEnvVar = cronDeliverEnvVar != null ? cronDeliverEnvVar : "";
    }

    public String getName() { return name; }
    public String getLabel() { return label; }
    public Function<Object, Object> getAdapterFactory() { return adapterFactory; }
    public Supplier<Boolean> getCheckFn() { return checkFn; }
    public Predicate<Object> getValidateConfig() { return validateConfig; }
    public List<String> getRequiredEnv() { return requiredEnv; }
    public String getInstallHint() { return installHint; }
    public String getSource() { return source; }
    public String getPluginName() { return pluginName; }
    public String getAllowedUsersEnv() { return allowedUsersEnv; }
    public String getAllowAllEnv() { return allowAllEnv; }
    public int getMaxMessageLength() { return maxMessageLength; }
    public boolean isPiiSafe() { return piiSafe; }
    public String getEmoji() { return emoji; }
    public boolean isAllowUpdateCommand() { return allowUpdateCommand; }
    public String getPlatformHint() { return platformHint; }
    public String getCronDeliverEnvVar() { return cronDeliverEnvVar; }

    public static Builder builder(String name, String label) {
        return new Builder(name, label);
    }

    public static class Builder {
        private final String name;
        private final String label;
        private Function<Object, Object> adapterFactory;
        private Supplier<Boolean> checkFn = () -> true;
        private Predicate<Object> validateConfig;
        private List<String> requiredEnv = List.of();
        private String installHint = "";
        private String source = "plugin";
        private String pluginName = "";
        private String allowedUsersEnv = "";
        private String allowAllEnv = "";
        private int maxMessageLength = 0;
        private boolean piiSafe = false;
        private String emoji = "🔌";
        private boolean allowUpdateCommand = true;
        private String platformHint = "";
        private String cronDeliverEnvVar = "";

        Builder(String name, String label) {
            this.name = name;
            this.label = label;
        }

        public Builder adapterFactory(Function<Object, Object> f) { this.adapterFactory = f; return this; }
        public Builder checkFn(Supplier<Boolean> f) { this.checkFn = f; return this; }
        public Builder validateConfig(Predicate<Object> f) { this.validateConfig = f; return this; }
        public Builder requiredEnv(List<String> v) { this.requiredEnv = v; return this; }
        public Builder installHint(String v) { this.installHint = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder pluginName(String v) { this.pluginName = v; return this; }
        public Builder allowedUsersEnv(String v) { this.allowedUsersEnv = v; return this; }
        public Builder allowAllEnv(String v) { this.allowAllEnv = v; return this; }
        public Builder maxMessageLength(int v) { this.maxMessageLength = v; return this; }
        public Builder piiSafe(boolean v) { this.piiSafe = v; return this; }
        public Builder emoji(String v) { this.emoji = v; return this; }
        public Builder allowUpdateCommand(boolean v) { this.allowUpdateCommand = v; return this; }
        public Builder platformHint(String v) { this.platformHint = v; return this; }
        public Builder cronDeliverEnvVar(String v) { this.cronDeliverEnvVar = v; return this; }

        public PlatformEntry build() {
            return new PlatformEntry(name, label, adapterFactory, checkFn, validateConfig,
                    requiredEnv, installHint, source, pluginName, allowedUsersEnv, allowAllEnv,
                    maxMessageLength, piiSafe, emoji, allowUpdateCommand, platformHint, cronDeliverEnvVar);
        }
    }
}
