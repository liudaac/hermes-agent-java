package com.nousresearch.hermes.plugin;

import com.nousresearch.hermes.plugin.context.PluginContext;

/**
 * Plugin entry-point contract. Every plugin jar/directory must expose
 * an implementation whose register() method is called by the host.
 *
 * In a jar-based plugin, the implementation class name is declared in
 * META-INF/services/com.nousresearch.hermes.plugin.Plugin.
 * In a directory-based plugin, the class is loaded from the plugin directory.
 */
public interface Plugin {
    /**
     * Register the plugin's tools, hooks, platforms, providers, etc.
     *
     * @param ctx host-provided facade for registration
     */
    void register(PluginContext ctx);
}
