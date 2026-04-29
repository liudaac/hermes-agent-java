package com.nousresearch.hermes.tenant.sandbox;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

/**
 * 进程沙箱配置
 */
public class ProcessSandboxConfig {

    private Path workDirectory;
    private Set<String> commandWhitelist = Collections.emptySet();
    private Set<String> commandBlacklist = Collections.emptySet();
    private boolean useCgroups = false; // Linux cgroups (需要 root 权限)

    public static ProcessSandboxConfig defaultConfig() {
        return new ProcessSandboxConfig();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ProcessSandboxConfig config = new ProcessSandboxConfig();

        public Builder workDirectory(Path path) {
            config.workDirectory = path;
            return this;
        }

        public Builder workDirectory(String path) {
            config.workDirectory = Paths.get(path);
            return this;
        }

        public Builder commandWhitelist(Set<String> whitelist) {
            config.commandWhitelist = whitelist;
            return this;
        }

        public Builder commandBlacklist(Set<String> blacklist) {
            config.commandBlacklist = blacklist;
            return this;
        }

        public Builder useCgroups(boolean use) {
            config.useCgroups = use;
            return this;
        }

        public ProcessSandboxConfig build() {
            return config;
        }
    }

    // Getters
    public Path getWorkDirectory() { return workDirectory; }
    public Set<String> getCommandWhitelist() { return commandWhitelist; }
    public Set<String> getCommandBlacklist() { return commandBlacklist; }
    public boolean isUseCgroups() { return useCgroups; }

    // Setters
    public void setWorkDirectory(Path workDirectory) { this.workDirectory = workDirectory; }
    public void setCommandWhitelist(Set<String> commandWhitelist) { this.commandWhitelist = commandWhitelist; }
    public void setCommandBlacklist(Set<String> commandBlacklist) { this.commandBlacklist = commandBlacklist; }
    public void setUseCgroups(boolean useCgroups) { this.useCgroups = useCgroups; }
}
