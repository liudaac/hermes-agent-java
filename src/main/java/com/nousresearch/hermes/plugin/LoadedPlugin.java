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

    public LoadedPlugin(PluginManifest manifest) {
        this.manifest = manifest;
        this.enabled = false;
        this.toolsRegistered = Collections.emptyList();
        this.hooksRegistered = Collections.emptyList();
        this.commandsRegistered = Collections.emptyList();
    }

    public LoadedPlugin(PluginManifest manifest, boolean enabled, String error) {
        this.manifest = manifest;
        this.enabled = enabled;
        this.error = error;
        this.toolsRegistered = Collections.emptyList();
        this.hooksRegistered = Collections.emptyList();
        this.commandsRegistered = Collections.emptyList();
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
}
