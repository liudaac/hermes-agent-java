package com.nousresearch.hermes.plugin.context;

import com.nousresearch.hermes.plugin.hook.HookCallback;
import com.nousresearch.hermes.plugin.hook.HookType;
import com.nousresearch.hermes.plugin.registry.NamedProvider;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;

import java.util.Map;
import java.util.function.Function;

/**
 * Facade given to plugins so they can register tools, hooks, platforms, providers,
 * commands, and access host-managed LLM without reaching into framework internals.
 *
 * Mirrors Python PluginContext.
 */
public interface PluginContext {

    // ------------------------------------------------------------------
    // Tool registration
    // ------------------------------------------------------------------
    void registerTool(String name, String toolset, Map<String, Object> schema,
                      Function<Map<String, Object>, Object> handler,
                      String description, String emoji, boolean override);

    // ------------------------------------------------------------------
    // Platform adapter registration
    // ------------------------------------------------------------------
    void registerPlatform(PlatformEntry entry);

    // ------------------------------------------------------------------
    // Provider registration (typed by category)
    // ------------------------------------------------------------------
    void registerProvider(String category, NamedProvider provider);

    // ------------------------------------------------------------------
    // Hook registration
    // ------------------------------------------------------------------
    void registerHook(HookType type, HookCallback callback);

    // ------------------------------------------------------------------
    // CLI / Slash command registration
    // ------------------------------------------------------------------
    void registerCliCommand(String name, String help, Runnable setupFn, Runnable handlerFn);
    void registerSlashCommand(String name, Function<String, Object> handler,
                               String description, String argsHint);

    // ------------------------------------------------------------------
    // Host-managed LLM access
    // ------------------------------------------------------------------
    Object getLlm(); // Returns PluginLlm facade (to be defined)

    // ------------------------------------------------------------------
    // Message injection
    // ------------------------------------------------------------------
    boolean injectMessage(String content, String role);

    // ------------------------------------------------------------------
    // Tool dispatch (for slash commands that need to call tools)
    // ------------------------------------------------------------------
    String dispatchTool(String toolName, Map<String, Object> args);

    // ------------------------------------------------------------------
    // Auxiliary task registration
    // ------------------------------------------------------------------
    void registerAuxiliaryTask(String key, String displayName, String description,
                                Map<String, Object> defaults);

    // ------------------------------------------------------------------
    // Skill registration (read-only plugin skills)
    // ------------------------------------------------------------------
    void registerSkill(String name, String path, String description);

    // ------------------------------------------------------------------
    // Context engine registration
    // ------------------------------------------------------------------
    void registerContextEngine(Object engine);

    // ------------------------------------------------------------------
    // Introspection
    // ------------------------------------------------------------------
    String getPluginName();
    String getPluginKey();
}
