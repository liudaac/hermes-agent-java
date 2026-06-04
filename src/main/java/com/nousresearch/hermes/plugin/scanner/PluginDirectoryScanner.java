package com.nousresearch.hermes.plugin.scanner;

import com.nousresearch.hermes.plugin.model.PluginManifest;
import com.nousresearch.hermes.plugin.model.Source;
import com.nousresearch.hermes.plugin.parser.PluginYamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Scans plugin directories for plugin.yaml manifests.
 * Supports two layouts (flat and category), matching Python _scan_directory.
 */
public class PluginDirectoryScanner {
    private static final Logger logger = LoggerFactory.getLogger(PluginDirectoryScanner.class);
    private final PluginYamlParser parser = new PluginYamlParser();

    /**
     * Scan a directory for plugins.
     *
     * @param path       root directory to scan
     * @param source     source label (bundled, user, project)
     * @param skipNames  top-level directory names to skip (can be null)
     * @return list of discovered manifests
     */
    public List<PluginManifest> scan(Path path, Source source, Set<String> skipNames) {
        return scanLevel(path, source, skipNames, "", 0);
    }

    private List<PluginManifest> scanLevel(Path path, Source source, Set<String> skipNames,
                                           String prefix, int depth) {
        List<PluginManifest> manifests = new ArrayList<>();
        if (!Files.isDirectory(path)) {
            return manifests;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            List<Path> children = new ArrayList<>();
            stream.forEach(children::add);
            children.sort(Comparator.comparing(p -> p.getFileName().toString()));

            for (Path child : children) {
                if (!Files.isDirectory(child)) continue;

                if (depth == 0 && skipNames != null && skipNames.contains(child.getFileName().toString())) {
                    continue;
                }

                Path manifestFile = child.resolve("plugin.yaml");
                if (!Files.exists(manifestFile)) {
                    manifestFile = child.resolve("plugin.yml");
                }

                if (Files.exists(manifestFile)) {
                    PluginManifest manifest = parser.parse(manifestFile, child, source, prefix);
                    if (manifest != null) {
                        manifests.add(manifest);
                    }
                    continue;
                }

                // No manifest at this level; recurse if within depth cap
                if (depth >= 1) {
                    logger.debug("Skipping {} (no plugin.yaml, depth cap reached)", child);
                    continue;
                }

                String subPrefix = prefix.isEmpty() ? child.getFileName().toString()
                        : prefix + "/" + child.getFileName().toString();
                manifests.addAll(scanLevel(child, source, null, subPrefix, depth + 1));
            }
        } catch (Exception e) {
            logger.debug("Directory scan failed for {}: {}", path, e.getMessage());
        }

        return manifests;
    }
}
