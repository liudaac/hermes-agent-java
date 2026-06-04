package com.nousresearch.hermes.plugin.loader;

import com.nousresearch.hermes.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads plugins from a directory containing jar files (or a single jar).
 * Uses an isolated {@link URLClassLoader} per plugin so the plugin's
 * dependencies don't leak into the host classpath.
 *
 * <p>Supports two entry-point discovery mechanisms:</p>
 * <ol>
 *   <li>{@code META-INF/services/com.nousresearch.hermes.plugin.Plugin}
 *       service descriptor (Java {@link ServiceLoader} convention)</li>
 *   <li>{@code plugin.properties} with key {@code main-class=com.example.MyPlugin}
 *       in the plugin directory</li>
 * </ol>
 */
public class JarPluginLoader {
    private static final Logger logger = LoggerFactory.getLogger(JarPluginLoader.class);

    /**
     * Load a plugin instance from the given plugin directory.
     * The directory may contain jar files (loaded into an isolated classloader)
     * and/or a plugin.properties hint file.
     *
     * @param pluginDir plugin root directory
     * @return Plugin instance, or null if no entry-point could be resolved
     */
    public Plugin loadPlugin(Path pluginDir) {
        List<URL> jars = findJars(pluginDir);
        if (jars.isEmpty()) {
            logger.debug("No jars found in plugin directory: {}", pluginDir);
            return null;
        }

        URLClassLoader classLoader = new URLClassLoader(
                jars.toArray(new URL[0]),
                getClass().getClassLoader()
        );

        // Strategy 1: ServiceLoader
        try {
            ServiceLoader<Plugin> serviceLoader = ServiceLoader.load(Plugin.class, classLoader);
            for (Plugin plugin : serviceLoader) {
                logger.info("Loaded plugin via ServiceLoader: {} ({})",
                        plugin.getClass().getName(), pluginDir.getFileName());
                return plugin;
            }
        } catch (Throwable t) {
            logger.debug("ServiceLoader discovery failed in {}: {}", pluginDir, t.getMessage());
        }

        // Strategy 2: plugin.properties with main-class hint
        Path propsFile = pluginDir.resolve("plugin.properties");
        if (Files.exists(propsFile)) {
            try {
                Properties props = new Properties();
                try (var in = Files.newInputStream(propsFile)) {
                    props.load(in);
                }
                String mainClass = props.getProperty("main-class");
                if (mainClass != null && !mainClass.isEmpty()) {
                    Class<?> clazz = classLoader.loadClass(mainClass);
                    if (Plugin.class.isAssignableFrom(clazz)) {
                        Plugin plugin = (Plugin) clazz.getDeclaredConstructor().newInstance();
                        logger.info("Loaded plugin via plugin.properties main-class: {} ({})",
                                mainClass, pluginDir.getFileName());
                        return plugin;
                    } else {
                        logger.warn("plugin.properties main-class {} does not implement Plugin", mainClass);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to load plugin.properties main-class for {}: {}",
                        pluginDir, e.getMessage());
            }
        }

        // Strategy 3: scan jars for META-INF/MANIFEST.MF Plugin-Class attribute
        for (URL jarUrl : jars) {
            try {
                Path jarPath = Path.of(jarUrl.toURI());
                try (JarFile jar = new JarFile(jarPath.toFile())) {
                    var manifest = jar.getManifest();
                    if (manifest != null) {
                        String mainClass = manifest.getMainAttributes().getValue("Plugin-Class");
                        if (mainClass != null && !mainClass.isEmpty()) {
                            Class<?> clazz = classLoader.loadClass(mainClass);
                            if (Plugin.class.isAssignableFrom(clazz)) {
                                Plugin plugin = (Plugin) clazz.getDeclaredConstructor().newInstance();
                                logger.info("Loaded plugin via JAR manifest Plugin-Class: {} ({})",
                                        mainClass, jarPath.getFileName());
                                return plugin;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not inspect manifest in {}: {}", jarUrl, e.getMessage());
            }
        }

        logger.warn("Plugin directory '{}' has jars but no resolvable Plugin entry-point", pluginDir);
        return null;
    }

    private List<URL> findJars(Path pluginDir) {
        List<URL> urls = new ArrayList<>();
        if (!Files.isDirectory(pluginDir)) {
            return urls;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDir, "*.jar")) {
            for (Path jar : stream) {
                if (Files.isRegularFile(jar)) {
                    try {
                        urls.add(jar.toUri().toURL());
                    } catch (Exception e) {
                        logger.warn("Failed to convert jar path to URL: {}", jar);
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("Could not enumerate jars in {}: {}", pluginDir, e.getMessage());
        }

        // Also include nested lib/ directory if present (for plugins with dependencies)
        Path libDir = pluginDir.resolve("lib");
        if (Files.isDirectory(libDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(libDir, "*.jar")) {
                for (Path jar : stream) {
                    if (Files.isRegularFile(jar)) {
                        try {
                            urls.add(jar.toUri().toURL());
                        } catch (Exception e) {
                            logger.warn("Failed to convert lib jar path to URL: {}", jar);
                        }
                    }
                }
            } catch (IOException e) {
                logger.debug("Could not enumerate lib jars in {}: {}", libDir, e.getMessage());
            }
        }

        return urls;
    }
}
