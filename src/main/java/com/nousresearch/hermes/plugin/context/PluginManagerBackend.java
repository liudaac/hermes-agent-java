package com.nousresearch.hermes.plugin.context;

import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.plugin.hook.HookType;
import com.nousresearch.hermes.plugin.registry.PlatformRegistry;
import com.nousresearch.hermes.plugin.registry.ProviderRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Backend contract for PluginContextImpl — provides access to host registries
 * and tracks what each plugin registered.
 */
public interface PluginManagerBackend {

    PlatformRegistry getPlatformRegistry();

    HookEngine getHookEngine();

    ProviderRegistry<?> getProviderRegistry(String category);

    ToolRegistryBridge getToolRegistry();

    void trackPluginTool(String pluginKey, String toolName);

    void trackPluginPlatform(String pluginKey, String platformName);

    void trackPluginHook(String pluginKey, HookType hookType);

    void trackCliCommand(String name, String help, Runnable setupFn, Runnable handlerFn, String pluginName);

    void trackSlashCommand(String name, Function<String, Object> handler, String description, String argsHint, String pluginName);

    void trackAuxiliaryTask(String key, String displayName, String description, Map<String, Object> defaults, String pluginName);

    void trackPluginSkill(String qualifiedName, Path path, String pluginName, String bareName, String description);

    Object getContextEngine();

    void setContextEngine(Object engine);

    /**
     * Bridge to tool registry (lightweight abstraction to avoid circular deps).
     */
    interface ToolRegistryBridge {
        void register(String name, String toolset, Map<String, Object> schema,
                      Function<Map<String, Object>, Object> handler,
                      String description, String emoji, boolean override);
    }
}
