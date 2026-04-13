package com.nousresearch.hermes.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * SkillHub client for remote skill installation and management.
 * Mirrors Python's skills_hub.py functionality.
 */
public class SkillHubClient {
    private static final Logger logger = LoggerFactory.getLogger(SkillHubClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    // Registry endpoints
    private static final String CLAWHUB_API = "https://api.clawhub.com/v1";
    private static final String SKILLHUB_API = "https://skillhub.tencent.com/api/v1";
    
    private final OkHttpClient httpClient;
    private final Path skillsDir;
    private String registryUrl;
    private String apiToken;
    
    public SkillHubClient(Path skillsDir) {
        this.skillsDir = skillsDir;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        
        // Load configuration
        this.registryUrl = System.getenv("SKILL_REGISTRY_URL");
        if (registryUrl == null) {
            // Default to SkillHub for Chinese users, ClawHub for others
            String locale = System.getenv("LANG");
            if (locale != null && locale.toLowerCase().contains("zh")) {
                this.registryUrl = SKILLHUB_API;
            } else {
                this.registryUrl = CLAWHUB_API;
            }
        }
        
        this.apiToken = System.getenv("SKILL_REGISTRY_TOKEN");
    }
    
    /**
     * Search for skills in the registry.
     */
    public List<SkillInfo> search(String query, int limit) throws IOException {
        String url = registryUrl + "/skills/search?q=" + query + "&limit=" + limit;
        
        Request.Builder requestBuilder = new Request.Builder().url(url);
        if (apiToken != null) {
            requestBuilder.header("Authorization", "Bearer " + apiToken);
        }
        
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Search failed: " + response.code());
            }
            
            JsonNode root = mapper.readTree(response.body().string());
            List<SkillInfo> results = new ArrayList<>();
            
            JsonNode skills = root.path("skills");
            if (skills.isArray()) {
                for (JsonNode skill : skills) {
                    results.add(new SkillInfo(
                        skill.path("id").asText(),
                        skill.path("name").asText(),
                        skill.path("description").asText(),
                        skill.path("version").asText(),
                        skill.path("author").asText(),
                        skill.path("downloads").asInt(0),
                        skill.path("rating").asDouble(0.0)
                    ));
                }
            }
            
            return results;
        }
    }
    
    /**
     * Install a skill from the registry.
     */
    public boolean install(String skillId, String version) throws IOException {
        logger.info("Installing skill: {} (version: {})", skillId, version != null ? version : "latest");
        
        // Get skill metadata
        String url = registryUrl + "/skills/" + skillId;
        if (version != null) {
            url += "/versions/" + version;
        }
        
        Request.Builder requestBuilder = new Request.Builder().url(url);
        if (apiToken != null) {
            requestBuilder.header("Authorization", "Bearer " + apiToken);
        }
        
        JsonNode skillData;
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Skill not found: " + skillId);
            }
            skillData = mapper.readTree(response.body().string());
        }
        
        // Download skill content
        String downloadUrl = skillData.path("download_url").asText();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            throw new IOException("No download URL for skill: " + skillId);
        }
        
        // Download and save
        Request downloadRequest = new Request.Builder().url(downloadUrl).build();
        try (Response response = httpClient.newCall(downloadRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Download failed: " + response.code());
            }
            
            String content = response.body().string();
            String filename = skillId + ".md";
            Path targetPath = skillsDir.resolve(filename);
            
            Files.createDirectories(skillsDir);
            Files.writeString(targetPath, content);
            
            logger.info("Skill installed: {} -> {}", skillId, targetPath);
            return true;
        }
    }
    
    /**
     * Update a skill to the latest version.
     */
    public boolean update(String skillId) throws IOException {
        logger.info("Updating skill: {}", skillId);
        return install(skillId, null);
    }
    
    /**
     * Uninstall a skill.
     */
    public boolean uninstall(String skillId) throws IOException {
        Path skillPath = skillsDir.resolve(skillId + ".md");
        if (Files.exists(skillPath)) {
            Files.delete(skillPath);
            logger.info("Skill uninstalled: {}", skillId);
            return true;
        }
        return false;
    }
    
    /**
     * List installed skills.
     */
    public List<String> listInstalled() throws IOException {
        List<String> skills = new ArrayList<>();
        if (Files.exists(skillsDir)) {
            try (var stream = Files.list(skillsDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                      .forEach(p -> skills.add(p.getFileName().toString().replace(".md", "")));
            }
        }
        return skills;
    }
    
    /**
     * Get skill info.
     */
    public SkillInfo getInfo(String skillId) throws IOException {
        String url = registryUrl + "/skills/" + skillId;
        
        Request.Builder requestBuilder = new Request.Builder().url(url);
        if (apiToken != null) {
            requestBuilder.header("Authorization", "Bearer " + apiToken);
        }
        
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            
            JsonNode skill = mapper.readTree(response.body().string());
            return new SkillInfo(
                skill.path("id").asText(),
                skill.path("name").asText(),
                skill.path("description").asText(),
                skill.path("version").asText(),
                skill.path("author").asText(),
                skill.path("downloads").asInt(0),
                skill.path("rating").asDouble(0.0)
            );
        }
    }
    
    /**
     * Sync all installed skills to latest versions.
     */
    public Map<String, Boolean> syncAll() throws IOException {
        Map<String, Boolean> results = new HashMap<>();
        List<String> installed = listInstalled();
        
        for (String skillId : installed) {
            try {
                boolean updated = update(skillId);
                results.put(skillId, updated);
            } catch (Exception e) {
                logger.error("Failed to update {}: {}", skillId, e.getMessage());
                results.put(skillId, false);
            }
        }
        
        return results;
    }
    
    // ==================== Skill Info Record ====================
    
    public record SkillInfo(
        String id,
        String name,
        String description,
        String version,
        String author,
        int downloads,
        double rating
    ) {
        @Override
        public String toString() {
            return String.format("%s@%s - %s (⭐ %.1f, ↓ %d)", 
                id, version, description, rating, downloads);
        }
    }
}
