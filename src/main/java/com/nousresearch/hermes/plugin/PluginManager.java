package com.nousresearch.hermes.plugin;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.plugin.context.PluginContextImpl;
import com.nousresearch.hermes.plugin.context.PluginManagerBackend;
import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.plugin.hook.HookType;
import com.nousresearch.hermes.plugin.loader.JarPluginLoader;
import com.nousresearch.hermes.plugin.model.PluginKind;
import com.nousresearch.hermes.plugin.model.PluginManifest;
import com.nousresearch.hermes.plugin.model.Source;
import com.nousresearch.hermes.plugin.registry.*;
import com.nousresearch.hermes.plugin.scanner.PluginDirectoryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central manager that discovers, loads, and invokes plugins.
 * Mirrors Python PluginManager.
 *
 * <p>Discovery sources (later override earlier on key collision):</p>
 * <ol>
 *   <li>Bundled plugins – {@code <hermes-home>/plugins/}</li>
 *   <li>User plugins – {@code ~/.hermes/plugins/}</li>
 *   <li>Project plugins – {@code ./.hermes/plugins/} (opt-in)</li>
 * </ol>
 *
 * <p>Loading strategy by kind + source:</p>
 * <ul>
 *   <li>{@code backend/platform} + bundled → <b>auto-load</b></li>
 *   <li>{@code exclusive} → <b>skip</b> (handled by category discovery)</li>
 *   <li>{@code model-provider} → <b>skip</b> (handled by provider discovery)</li>
 *   <li>everything else → <b>opt-in</b> via {@code plugins.enabled}</li>
 * </ul>
 */
public class PluginManager implements PluginManagerBackend {
    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);
    private static volatile PluginManager INSTANCE;

    private final PluginDirectoryScanner scanner = new PluginDirectoryScanner();
    private final JarPluginLoader jarLoader = new JarPluginLoader();
    private final PlatformRegistry platformRegistry = new PlatformRegistry();
    private final HookEngine hookEngine = new HookEngine();
    private final Map<String, ProviderRegistry<?>> providerRegistries = new ConcurrentHashMap<>();
    private final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();
    private final Set<String> pluginToolNames = ConcurrentHashMap.newKeySet();
    private final Set<String> pluginPlatformNames = ConcurrentHashMap.newKeySet();
    private final Map<String, List<HookType>> pluginHookTypes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> cliCommands = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> slashCommands = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> pluginSkills = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> auxTasks = new ConcurrentHashMap<>();
    private volatile Object contextEngine = null;
    private volatile boolean discovered = false;

    private final HermesConfig config;
    private final Path bundledDir;
    private final Path userDir;
    private final boolean projectPluginsEnabled;
    private final Path projectDir;

    public PluginManager(HermesConfig config) {
        this.config = config;
        this.bundledDir = resolveBundledPluginsDir();
        this.userDir = resolveUserPluginsDir();
        this.projectPluginsEnabled = isEnvEnabled("HERMES_ENABLE_PROJECT_PLUGINS");
        this.projectDir = Paths.get(".").toAbsolutePath().normalize().resolve(".hermes").resolve("plugins");
        INSTANCE = this;
    }

    /**
     * Get the global PluginManager instance (set during construction).
     */
    public static PluginManager getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Discovery & Load
    // ------------------------------------------------------------------

    public synchronized void discoverAndLoad(boolean force) {
        if (discovered && !force) return;
        if (force) clearState();
        discovered = true;

        List<PluginManifest> manifests = new ArrayList<>();

        // 1. Bundled plugins
        logger.debug("Scanning bundled plugins: {}", bundledDir);
        List<PluginManifest> bundled = scanner.scan(bundledDir, Source.BUNDLED,
                Set.of("memory", "context_engine", "platforms", "model-providers"));
        logger.debug("  bundled (top-level): {} manifest(s)", bundled.size());
        manifests.addAll(bundled);

        List<PluginManifest> bundledPlatforms = scanner.scan(bundledDir.resolve("platforms"), Source.BUNDLED, null);
        logger.debug("  bundled/platforms: {} manifest(s)", bundledPlatforms.size());
        manifests.addAll(bundledPlatforms);

        // 2. User plugins
        logger.debug("Scanning user plugins: {}", userDir);
        List<PluginManifest> user = scanner.scan(userDir, Source.USER, null);
        logger.debug("  user: {} manifest(s)", user.size());
        manifests.addAll(user);

        // 3. Project plugins
        if (projectPluginsEnabled) {
            logger.debug("Scanning project plugins: {}", projectDir);
            List<PluginManifest> project = scanner.scan(projectDir, Source.PROJECT, null);
            logger.debug("  project: {} manifest(s)", project.size());
            manifests.addAll(project);
        } else {
            logger.debug("Project plugins disabled (set HERMES_ENABLE_PROJECT_PLUGINS=1 to enable)");
        }

        // 4. Deduplicate: later sources override earlier ones
        Map<String, PluginManifest> winners = new LinkedHashMap<>();
        for (PluginManifest m : manifests) {
            String key = m.getKey() != null && !m.getKey().isEmpty() ? m.getKey() : m.getName();
            winners.put(key, m);
        }

        Set<String> disabled = getDisabledPlugins();
        Set<String> enabled = getEnabledPlugins(); // null = opt-in default (nothing enabled)

        for (PluginManifest manifest : winners.values()) {
            String key = manifest.getKey() != null && !manifest.getKey().isEmpty()
                    ? manifest.getKey() : manifest.getName();

            // Explicit disable always wins
            if (disabled.contains(key) || disabled.contains(manifest.getName())) {
                recordSkipped(key, manifest, "disabled via config");
                continue;
            }

            // Exclusive plugins handled by category discovery
            if (manifest.getKind() == PluginKind.EXCLUSIVE) {
                recordSkipped(key, manifest, "exclusive — handled by category discovery");
                continue;
            }

            // Model provider plugins handled by provider discovery
            if (manifest.getKind() == PluginKind.MODEL_PROVIDER) {
                recordEnabled(key, manifest); // record for introspection, don't load module
                logger.debug("Skipping '{}' (model-provider, handled by providers discovery)", key);
                continue;
            }

            // Bundled backends and platforms auto-load
            if (manifest.getSource() == Source.BUNDLED
                    && (manifest.getKind() == PluginKind.BACKEND || manifest.getKind() == PluginKind.PLATFORM)) {
                loadPlugin(manifest);
                continue;
            }

            // Everything else is opt-in via plugins.enabled
            boolean isEnabled = enabled != null
                    && (enabled.contains(key) || enabled.contains(manifest.getName()));
            if (!isEnabled) {
                recordSkipped(key, manifest, "not enabled in config");
                continue;
            }
            loadPlugin(manifest);
        }

        if (!manifests.isEmpty()) {
            long enabledCount = plugins.values().stream().filter(LoadedPlugin::isEnabled).count();
            logger.info("Plugin discovery complete: {} found, {} enabled",
                    plugins.size(), enabledCount);
        }
    }

    public void discoverAndLoad() {
        discoverAndLoad(false);
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void loadPlugin(PluginManifest manifest) {
        String key = manifest.getKey() != null && !manifest.getKey().isEmpty()
                ? manifest.getKey() : manifest.getName();
        LoadedPlugin loaded = new LoadedPlugin(manifest);
        logger.debug("Loading plugin '{}' (source={}, kind={}, path={})",
                key, manifest.getSource(), manifest.getKind(), manifest.getPath());

        try {
            Plugin plugin = instantiatePlugin(manifest);
            PluginContextImpl ctx = new PluginContextImpl(manifest, this);
            plugin.register(ctx);
            loaded.setEnabled(true);
            loaded.setToolsRegistered(new ArrayList<>(pluginToolNames));
            loaded.setHooksRegistered(pluginHookTypes.getOrDefault(key, List.of()).stream()
                    .map(HookType::name).toList());
            logger.debug("  registered: tools={}, hooks={}",
                    loaded.getToolsRegistered().size(), loaded.getHooksRegistered().size());
        } catch (Exception e) {
            loaded.setEnabled(false);
            loaded.setError(e.getMessage());
            logger.error("Failed to load plugin '{}': {}", manifest.getName(), e.getMessage(), e);
        }

        plugins.put(key, loaded);
    }

    private Plugin instantiatePlugin(PluginManifest manifest) {
        // Strategy 1: look for a Java class implementing Plugin in the plugin directory
        Path pluginDir = manifest.getPath();
        if (pluginDir == null) {
            throw new IllegalStateException("Plugin path is null");
        }

        // Strategy 0: external jar loading (user/project plugins with bundled jars)
        if (manifest.getSource() != Source.BUNDLED) {
            Plugin jarPlugin = jarLoader.loadPlugin(pluginDir);
            if (jarPlugin != null) {
                return jarPlugin;
            }
        }

        // Strategy 1: derived class name in main classpath
        String key = manifest.getKey() != null ? manifest.getKey() : manifest.getName();
        String className = deriveClassName(key);
        try {
            Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (Plugin.class.isAssignableFrom(clazz)) {
                return (Plugin) clazz.getDeclaredConstructor().newInstance();
            }
        } catch (ClassNotFoundException e) {
            logger.debug("Plugin class not found: {}", className);
        } catch (Exception e) {
            logger.debug("Failed to instantiate plugin class {}: {}", className, e.getMessage());
        }

        // Strategy 2: built-in classpath lookup for bundled plugins
        // This allows bundled plugins to live as regular Java classes in the main jar
        String[] builtinCandidates = deriveBuiltinClassNames(key);
        for (String builtinClassName : builtinCandidates) {
            try {
                Class<?> clazz = getClass().getClassLoader().loadClass(builtinClassName);
                if (Plugin.class.isAssignableFrom(clazz)) {
                    return (Plugin) clazz.getDeclaredConstructor().newInstance();
                }
            } catch (ClassNotFoundException e) {
                logger.debug("Built-in plugin class not found: {}", builtinClassName);
            } catch (Exception e) {
                logger.debug("Failed to instantiate built-in plugin class {}: {}", builtinClassName, e.getMessage());
            }
        }

        // Strategy 3: fallback to jar loading even for bundled (in case dev mode)
        Plugin jarPluginFallback = jarLoader.loadPlugin(pluginDir);
        if (jarPluginFallback != null) {
            return jarPluginFallback;
        }

        throw new IllegalStateException(
                "No Plugin implementation found for " + key + 
                " (tried jar discovery, " + className + ", and built-in variants)");
    }

    private String[] deriveBuiltinClassNames(String key) {
        // key like "platforms/feishu" or "feishu"
        String base = key.replace("platforms/", "").replace("/", ".").replace("-", "_");
        String pkg = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base;
        String name = base.contains(".") ? base.substring(base.lastIndexOf('.') + 1) : base;
        String className = name.substring(0, 1).toUpperCase() + name.substring(1) + "PlatformPlugin";
        return new String[]{
            "com.nousresearch.hermes.plugin.builtin." + pkg + "." + className,
            "com.nousresearch.hermes.plugin.builtin." + toClassName(key) + "PlatformPlugin",
            "com.nousresearch.hermes.plugin.builtin." + toClassName(key)
        };
    }

    private String deriveClassName(String key) {
        // key like "image_gen/openai" → package style or flat
        String sanitized = key.replace("/", ".").replace("-", "_");
        return "com.nousresearch.hermes.plugin." + sanitized + ".PluginImpl";
    }

    private String toClassName(String key) {
        // Convert key to valid Java class name
        return Arrays.stream(key.split("[/_-]"))
                .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1))
                .collect(Collectors.joining(""));
    }

    // ------------------------------------------------------------------
    // State management
    // ------------------------------------------------------------------

    private void clearState() {
        plugins.clear();
        pluginToolNames.clear();
        pluginPlatformNames.clear();
        pluginHookTypes.clear();
        cliCommands.clear();
        slashCommands.clear();
        pluginSkills.clear();
        auxTasks.clear();
        contextEngine = null;
    }

    private void recordSkipped(String key, PluginManifest manifest, String reason) {
        LoadedPlugin lp = new LoadedPlugin(manifest, false, reason);
        plugins.put(key, lp);
        logger.debug("Skipping '{}' ({})", key, reason);
    }

    private void recordEnabled(String key, PluginManifest manifest) {
        LoadedPlugin lp = new LoadedPlugin(manifest, true, null);
        plugins.put(key, lp);
    }

    // ------------------------------------------------------------------
    // Config helpers
    // ------------------------------------------------------------------

    private Set<String> getDisabledPlugins() {
        try {
            Object raw = config.getConfigValue("plugins.disabled");
            if (raw instanceof List<?> list) {
                return list.stream().map(Object::toString).collect(Collectors.toSet());
            }
        } catch (Exception ignored) {}
        return Set.of();
    }

    @SuppressWarnings("unchecked")
    private Set<String> getEnabledPlugins() {
        // Returns null when key is missing (opt-in default)
        try {
            Object pluginsCfg = config.getConfigValue("plugins");
            if (!(pluginsCfg instanceof Map)) return null;
            Map<String, Object> m = (Map<String, Object>) pluginsCfg;
            if (!m.containsKey("enabled")) return null;
            Object enabled = m.get("enabled");
            if (enabled instanceof List<?> list) {
                return list.stream().map(Object::toString).collect(Collectors.toSet());
            }
            return null;
        } catch (Exception ignored) {}
        return null;
    }

    private Path resolveBundledPluginsDir() {
        String env = System.getenv("HERMES_BUNDLED_PLUGINS");
        if (env != null && !env.isEmpty()) {
            return Paths.get(env);
        }
        // Default: <jar-dir>/plugins (or repo-root/plugins during dev)
        return Paths.get(System.getProperty("user.dir"), "plugins");
    }

    private Path resolveUserPluginsDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".hermes", "plugins");
    }

    private boolean isEnvEnabled(String name) {
        String v = System.getenv(name);
        if (v == null) return false;
        String s = v.trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on");
    }

    // ------------------------------------------------------------------
    // PluginManagerBackend implementation
    // ------------------------------------------------------------------

    @Override
    public PlatformRegistry getPlatformRegistry() {
        return platformRegistry;
    }

    @Override
    public HookEngine getHookEngine() {
        return hookEngine;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ProviderRegistry<?> getProviderRegistry(String category) {
        return providerRegistries.computeIfAbsent(category,
                k -> new ProviderRegistry<>(k));
    }

    @Override
    public ToolRegistryBridge getToolRegistry() {
        return new ToolRegistryBridge() {
            @Override
            @SuppressWarnings("unchecked")
            public void register(String name, String toolset, Map<String, Object> schema,
                                 Function<Map<String, Object>, Object> handler,
                                 String description, String emoji, boolean override) {
                com.nousresearch.hermes.tools.ToolRegistry registry =
                        com.nousresearch.hermes.tools.ToolRegistry.getInstance();
                if (!override && registry.getSchema(name) != null) {
                    logger.warn("Tool '{}' already registered and override=false; skipping", name);
                    return;
                }
                Function<Map<String, Object>, String> stringHandler = (args) -> {
                    Object result = handler.apply(args);
                    return result != null ? result.toString() : "";
                };
                registry.register(new com.nousresearch.hermes.tools.ToolEntry.Builder()
                        .name(name)
                        .toolset(toolset)
                        .schema(schema)
                        .handler(stringHandler)
                        .description(description != null ? description : "")
                        .emoji(emoji != null && !emoji.isEmpty() ? emoji : "⚡")
                        .build());
                logger.debug("Tool registered: {} (toolset={}, override={})", name, toolset, override);
            }
        };
    }

    @Override
    public void trackPluginTool(String pluginKey, String toolName) {
        pluginToolNames.add(toolName);
    }

    @Override
    public void trackPluginPlatform(String pluginKey, String platformName) {
        pluginPlatformNames.add(platformName);
    }

    @Override
    public void trackPluginHook(String pluginKey, HookType hookType) {
        pluginHookTypes.computeIfAbsent(pluginKey, k -> new ArrayList<>()).add(hookType);
    }

    @Override
    public void trackCliCommand(String name, String help, Runnable setupFn, Runnable handlerFn, String pluginName) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("name", name);
        cmd.put("help", help);
        cmd.put("setupFn", setupFn);
        cmd.put("handlerFn", handlerFn);
        cmd.put("plugin", pluginName);
        cliCommands.put(name, cmd);
    }

    @Override
    public void trackSlashCommand(String name, Function<String, Object> handler,
                                   String description, String argsHint, String pluginName) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("handler", handler);
        cmd.put("description", description);
        cmd.put("plugin", pluginName);
        cmd.put("args_hint", argsHint);
        slashCommands.put(name, cmd);
    }

    @Override
    public void trackAuxiliaryTask(String key, String displayName, String description,
                                    Map<String, Object> defaults, String pluginName) {
        Map<String, Object> task = new HashMap<>();
        task.put("key", key);
        task.put("display_name", displayName);
        task.put("description", description);
        task.put("defaults", defaults != null ? defaults : Map.of());
        task.put("plugin", pluginName);
        auxTasks.put(key, task);
    }

    @Override
    public void trackPluginSkill(String qualifiedName, Path path, String pluginName,
                                  String bareName, String description) {
        Map<String, Object> skill = new HashMap<>();
        skill.put("path", path);
        skill.put("plugin", pluginName);
        skill.put("bare_name", bareName);
        skill.put("description", description);
        pluginSkills.put(qualifiedName, skill);
    }

    @Override
    public Object getContextEngine() {
        return contextEngine;
    }

    @Override
    public void setContextEngine(Object engine) {
        this.contextEngine = engine;
    }

    // ------------------------------------------------------------------
    // Introspection
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listPlugins() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, LoadedPlugin> e : plugins.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            LoadedPlugin p = e.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", p.getManifest().getName());
            info.put("key", e.getKey());
            info.put("kind", p.getManifest().getKind().name().toLowerCase());
            info.put("version", p.getManifest().getVersion());
            info.put("description", p.getManifest().getDescription());
            info.put("source", p.getManifest().getSource().getValue());
            info.put("enabled", p.isEnabled());
            info.put("tools", p.getToolsRegistered().size());
            info.put("hooks", p.getHooksRegistered().size());
            info.put("commands", p.getCommandsRegistered().size());
            info.put("error", p.getError());
            result.add(info);
        }
        return result;
    }

    public Map<String, LoadedPlugin> getPlugins() {
        return Map.copyOf(plugins);
    }

    public PlatformRegistry getPlatformRegistryFacade() {
        return platformRegistry;
    }

    public HookEngine getHookEngineFacade() {
        return hookEngine;
    }

    public Map<String, Map<String, Object>> getSlashCommands() {
        return Map.copyOf(slashCommands);
    }

    public Optional<Function<String, Object>> getSlashCommandHandler(String name) {
        Map<String, Object> entry = slashCommands.get(name);
        if (entry == null) return Optional.empty();
        @SuppressWarnings("unchecked")
        Function<String, Object> handler = (Function<String, Object>) entry.get("handler");
        return Optional.ofNullable(handler);
    }

    public Map<String, Map<String, Object>> getAuxiliaryTasks() {
        return Map.copyOf(auxTasks);
    }
}
