package com.nousresearch.hermes.plugin.context;

import com.nousresearch.hermes.plugin.hook.HookCallback;
import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.plugin.hook.HookType;
import com.nousresearch.hermes.plugin.model.PluginManifest;
import com.nousresearch.hermes.plugin.registry.NamedProvider;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;
import com.nousresearch.hermes.plugin.registry.PlatformRegistry;
import com.nousresearch.hermes.plugin.registry.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Implementation of PluginContext handed to each plugin's register() function.
 */
public class PluginContextImpl implements PluginContext {
    private static final Logger logger = LoggerFactory.getLogger(PluginContextImpl.class);

    private final PluginManifest manifest;
    private final PluginManagerBackend backend;

    public PluginContextImpl(PluginManifest manifest, PluginManagerBackend backend) {
        this.manifest = manifest;
        this.backend = backend;
    }

    @Override
    public void registerTool(String name, String toolset, Map<String, Object> schema,
                             Function<Map<String, Object>, Object> handler,
                             String description, String emoji, boolean override) {
        // Delegate to tool registry (to be wired)
        backend.getToolRegistry().register(name, toolset, schema, handler, description, emoji, override);
        backend.trackPluginTool(manifest.getKey(), name);
        logger.debug("Plugin {} registered tool: {}{}", manifest.getName(), name,
                override ? " (override)" : "");
    }

    @Override
    public void registerPlatform(PlatformEntry entry) {
        backend.getPlatformRegistry().register(entry.getName(), entry);
        backend.trackPluginPlatform(manifest.getKey(), entry.getName());
        logger.debug("Plugin {} registered platform: {}", manifest.getName(), entry.getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void registerProvider(String category, NamedProvider provider) {
        ProviderRegistry<NamedProvider> registry = (ProviderRegistry<NamedProvider>) backend.getProviderRegistry(category);
        if (registry != null) {
            registry.register(provider.getName(), provider);
            logger.info("Plugin {} registered {} provider: {}",
                    manifest.getName(), category, provider.getName());
        } else {
            logger.warn("Unknown provider category '{}' for plugin {}", category, manifest.getName());
        }
    }

    @Override
    public void registerHook(HookType type, HookCallback callback) {
        backend.getHookEngine().register(type, callback);
        backend.trackPluginHook(manifest.getKey(), type);
        logger.debug("Plugin {} registered hook: {}", manifest.getName(), type);
    }

    @Override
    public void registerCliCommand(String name, String help, Runnable setupFn, Runnable handlerFn) {
        backend.trackCliCommand(name, help, setupFn, handlerFn, manifest.getName());
        logger.debug("Plugin {} registered CLI command: {}", manifest.getName(), name);
    }

    @Override
    public void registerSlashCommand(String name, Function<String, Object> handler,
                                      String description, String argsHint) {
        String clean = name.toLowerCase().strip().replaceAll("^/+", "").replace(" ", "-");
        if (clean.isEmpty()) {
            logger.warn("Plugin '{}' tried to register command with empty name", manifest.getName());
            return;
        }
        backend.trackSlashCommand(clean, handler, description, argsHint, manifest.getName());
        logger.debug("Plugin {} registered slash command: /{}", manifest.getName(), clean);
    }

    @Override
    public Object getLlm() {
        // TODO: return PluginLlm facade backed by user's active model
        logger.debug("Plugin {} requested LLM facade (not yet implemented)", manifest.getName());
        return null;
    }

    @Override
    public boolean injectMessage(String content, String role) {
        logger.debug("Plugin {} injected message (role={})", manifest.getName(), role);
        // TODO: wire to active conversation / CLI
        return false;
    }

    @Override
    public String dispatchTool(String toolName, Map<String, Object> args) {
        // TODO: delegate to tool registry dispatch
        logger.debug("Plugin {} dispatched tool: {}", manifest.getName(), toolName);
        return "{}";
    }

    @Override
    public void registerAuxiliaryTask(String key, String displayName, String description,
                                      Map<String, Object> defaults) {
        backend.trackAuxiliaryTask(key, displayName, description, defaults, manifest.getName());
        logger.debug("Plugin {} registered auxiliary task: {} ({})",
                manifest.getName(), key, displayName);
    }

    @Override
    public void registerSkill(String name, String path, String description) {
        if (name.contains(":")) {
            throw new IllegalArgumentException("Skill name must not contain ':'");
        }
        String qualified = manifest.getName() + ":" + name;
        backend.trackPluginSkill(qualified, Paths.get(path), manifest.getName(), name, description);
        logger.debug("Plugin {} registered skill: {}", manifest.getName(), qualified);
    }

    @Override
    public void registerContextEngine(Object engine) {
        if (backend.getContextEngine() != null) {
            logger.warn("Plugin '{}' tried to register context engine but one already exists", manifest.getName());
            return;
        }
        backend.setContextEngine(engine);
        logger.info("Plugin '{}' registered context engine", manifest.getName());
    }

    @Override
    public String getPluginName() {
        return manifest.getName();
    }

    @Override
    public String getPluginKey() {
        return manifest.getKey() != null && !manifest.getKey().isEmpty()
                ? manifest.getKey() : manifest.getName();
    }
}
