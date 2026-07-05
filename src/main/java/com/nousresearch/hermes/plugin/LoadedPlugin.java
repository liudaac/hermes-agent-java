package com.nousresearch.hermes.plugin;

import com.nousresearch.hermes.plugin.model.PluginManifest;

import java.util.Collections;
import java.util.List;

/**
 * Runtime state for a single loaded plugin.
 * Mirrors Python LoadedPlugin dataclass.
 */
public class LoadedPlugin {
    private final PluginManifest manifest;
    private boolean enabled;
    private String error;
    private List<String> toolsRegistered;
    private List<String> hooksRegistered;
    private List<String> commandsRegistered;
    /**
     * S1-5: Whether this plugin is allowed to override built-in tools.
     * Set via plugins.entries.&lt;key&gt;.allow_tool_override in config,
     * or via CLI --allow-tool-override flag, or auto-true for bundled plugins.
     */
    private boolean allowToolOverride;

    public LoadedPlugin(PluginManifest manifest) {
        this.manifest = manifest;
        this.enabled = false;
        this.toolsRegistered = Collections.emptyList();
        this.hooksRegistered = Collections.emptyList();
        this.commandsRegistered = Collections.emptyList();
        this.allowToolOverride = false;
    }

    public LoadedPlugin(PluginManifest manifest, boolean enabled, String error) {
        this.manifest = manifest;
        this.enabled = enabled;
        this.error = error;
        this.toolsRegistered = Collections.emptyList();
        this.hooksRegistered = Collections.emptyList();
        this.commandsRegistered = Collections.emptyList();
        this.allowToolOverride = false;
    }

    public PluginManifest getManifest() { return manifest; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public List<String> getToolsRegistered() { return toolsRegistered; }
    public void setToolsRegistered(List<String> toolsRegistered) { this.toolsRegistered = toolsRegistered; }
    public List<String> getHooksRegistered() { return hooksRegistered; }
    public void setHooksRegistered(List<String> hooksRegistered) { this.hooksRegistered = hooksRegistered; }
    public List<String> getCommandsRegistered() { return commandsRegistered; }
    public void setCommandsRegistered(List<String> commandsRegistered) { this.commandsRegistered = commandsRegistered; }
    public boolean isAllowToolOverride() { return allowToolOverride; }
    public void setAllowToolOverride(boolean allowToolOverride) { this.allowToolOverride = allowToolOverride; }
}
