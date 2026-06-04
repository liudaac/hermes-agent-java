package com.nousresearch.hermes.plugin.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Parsed representation of a plugin.yaml manifest.
 * Mirrors Python PluginManifest dataclass.
 */
public class PluginManifest {
    private String name;
    private String version;
    private String description;
    private String author;
    private List<EnvVarRequirement> requiresEnv;
    private List<EnvVarRequirement> optionalEnv;
    private List<String> providesTools;
    private List<String> providesHooks;
    private Source source;
    private Path path;
    private PluginKind kind;
    private String key;

    public PluginManifest() {
        this.version = "";
        this.description = "";
        this.author = "";
        this.requiresEnv = Collections.emptyList();
        this.optionalEnv = Collections.emptyList();
        this.providesTools = Collections.emptyList();
        this.providesHooks = Collections.emptyList();
        this.source = Source.BUNDLED;
        this.kind = PluginKind.STANDALONE;
        this.key = "";
    }

    public PluginManifest(String name, String version, String description, String author,
                          List<EnvVarRequirement> requiresEnv, List<EnvVarRequirement> optionalEnv,
                          List<String> providesTools, List<String> providesHooks,
                          Source source, Path path, PluginKind kind, String key) {
        this.name = name;
        this.version = version != null ? version : "";
        this.description = description != null ? description : "";
        this.author = author != null ? author : "";
        this.requiresEnv = requiresEnv != null ? requiresEnv : Collections.emptyList();
        this.optionalEnv = optionalEnv != null ? optionalEnv : Collections.emptyList();
        this.providesTools = providesTools != null ? providesTools : Collections.emptyList();
        this.providesHooks = providesHooks != null ? providesHooks : Collections.emptyList();
        this.source = source != null ? source : Source.BUNDLED;
        this.path = path;
        this.kind = kind != null ? kind : PluginKind.STANDALONE;
        this.key = key != null ? key : "";
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public List<EnvVarRequirement> getRequiresEnv() { return requiresEnv; }
    public void setRequiresEnv(List<EnvVarRequirement> requiresEnv) { this.requiresEnv = requiresEnv; }
    public List<EnvVarRequirement> getOptionalEnv() { return optionalEnv; }
    public void setOptionalEnv(List<EnvVarRequirement> optionalEnv) { this.optionalEnv = optionalEnv; }
    public List<String> getProvidesTools() { return providesTools; }
    public void setProvidesTools(List<String> providesTools) { this.providesTools = providesTools; }
    public List<String> getProvidesHooks() { return providesHooks; }
    public void setProvidesHooks(List<String> providesHooks) { this.providesHooks = providesHooks; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Path getPath() { return path; }
    public void setPath(Path path) { this.path = path; }
    public PluginKind getKind() { return kind; }
    public void setKind(PluginKind kind) { this.kind = kind; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    @Override
    public String toString() {
        return "PluginManifest{name='" + name + "', key='" + key + "', kind=" + kind + ", source=" + source + "}";
    }
}
