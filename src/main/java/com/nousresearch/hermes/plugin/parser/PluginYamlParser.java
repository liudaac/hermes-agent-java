package com.nousresearch.hermes.plugin.parser;

import com.nousresearch.hermes.plugin.model.EnvVarRequirement;
import com.nousresearch.hermes.plugin.model.PluginKind;
import com.nousresearch.hermes.plugin.model.PluginManifest;
import com.nousresearch.hermes.plugin.model.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses plugin.yaml manifests into PluginManifest objects.
 * Mirrors Python _parse_manifest in hermes_cli/plugins.py.
 */
public class PluginYamlParser {
    private static final Logger logger = LoggerFactory.getLogger(PluginYamlParser.class);
    private static final int INIT_FILE_PREVIEW_BYTES = 8192;

    private final Yaml yaml = new Yaml();

    /**
     * Parse a single plugin.yaml into a PluginManifest.
     *
     * @param manifestFile path to plugin.yaml
     * @param pluginDir    plugin root directory
     * @param source       discovery source
     * @param prefix       category prefix (e.g. "image_gen"), empty string for flat
     * @return parsed manifest, or null on failure
     */
    public PluginManifest parse(Path manifestFile, Path pluginDir, Source source, String prefix) {
        try {
            String text = Files.readString(manifestFile, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = yaml.load(text);
            if (data == null) data = new HashMap<>();

            String name = stringValue(data, "name", pluginDir.getFileName().toString());
            String key = prefix.isEmpty() ? name : prefix + "/" + pluginDir.getFileName().toString();

            PluginKind kind = PluginKind.fromString(stringValue(data, "kind", null));

            // Heuristic: auto-detect memory / model provider from __init__.py content
            if (kind == PluginKind.STANDALONE && !data.containsKey("kind")) {
                kind = detectKindFromInit(pluginDir, key);
            }

            PluginManifest manifest = new PluginManifest();
            manifest.setName(name);
            manifest.setVersion(stringValue(data, "version", ""));
            manifest.setDescription(stringValue(data, "description", ""));
            manifest.setAuthor(stringValue(data, "author", ""));
            manifest.setRequiresEnv(parseEnvList(data.get("requires_env")));
            manifest.setOptionalEnv(parseEnvList(data.get("optional_env")));
            manifest.setProvidesTools(stringList(data, "provides_tools"));
            manifest.setProvidesHooks(stringList(data, "provides_hooks"));
            manifest.setSource(source);
            manifest.setPath(pluginDir);
            manifest.setKind(kind);
            manifest.setKey(key);

            logger.debug("Parsed manifest: key={} name={} kind={} source={} path={}",
                    key, name, kind, source, pluginDir);
            return manifest;

        } catch (IOException e) {
            logger.warn("Failed to read {}: {}", manifestFile, e.getMessage());
            return null;
        } catch (Exception e) {
            logger.warn("Failed to parse {}: {}", manifestFile, e.getMessage());
            return null;
        }
    }

    private PluginKind detectKindFromInit(Path pluginDir, String key) {
        Path initFile = pluginDir.resolve("__init__.py"); // In Java plugins we may have init hints
        if (!Files.exists(initFile)) {
            // For Java-based plugins, check for a marker file or class hints
            initFile = pluginDir.resolve("plugin.properties");
        }
        if (!Files.exists(initFile)) {
            return PluginKind.STANDALONE;
        }
        try {
            String text = Files.readString(initFile, StandardCharsets.UTF_8);
            String preview = text.length() > INIT_FILE_PREVIEW_BYTES
                    ? text.substring(0, INIT_FILE_PREVIEW_BYTES)
                    : text;
            if (preview.contains("register_memory_provider") || preview.contains("MemoryProvider")) {
                logger.debug("Plugin {}: detected memory provider, treating as exclusive", key);
                return PluginKind.EXCLUSIVE;
            }
            if (preview.contains("register_provider") && preview.contains("ProviderProfile")) {
                logger.debug("Plugin {}: detected model provider, treating as model-provider", key);
                return PluginKind.MODEL_PROVIDER;
            }
        } catch (IOException ignored) {
        }
        return PluginKind.STANDALONE;
    }

    @SuppressWarnings("unchecked")
    private List<EnvVarRequirement> parseEnvList(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(EnvVarRequirement::fromMap)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Map<String, Object> data, String key) {
        Object raw = data.get(key);
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private String stringValue(Map<String, Object> data, String key, String defaultValue) {
        Object v = data.get(key);
        return v != null ? v.toString() : defaultValue;
    }
}
