package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillsHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("/api/skills should list global workspace skills without synthetic builtins")
    void listsGlobalWorkspaceSkillsOnly() throws Exception {
        Path skillsDir = tempDir.resolve("workspace").resolve("skills");
        writeSkill(skillsDir.resolve("alpha"), "alpha-skill", "Alpha from frontmatter");
        Files.createDirectories(skillsDir.resolve("not-a-skill"));

        SkillsHandler handler = new SkillsHandler(skillsDir);
        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.get("/api/skills", handler::getSkills);

        try {
            app.start("127.0.0.1", port);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/skills")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            JSONArray skills = JSON.parseArray(response.body());
            assertEquals(1, skills.size(), "only directories with SKILL.md should be listed");

            JSONObject skill = skills.getJSONObject(0);
            assertEquals("alpha-skill", skill.getString("name"));
            assertEquals("Alpha from frontmatter", skill.getString("description"));
            assertEquals("global", skill.getString("scope"));
            assertEquals("workspace", skill.getString("source"));
            assertFalse(skill.getBooleanValue("builtin"));
            assertTrue(skill.getString("path").endsWith("/alpha"));
            assertEquals(skillsDir.toAbsolutePath().normalize().toString(), skill.getString("skillsDir"));
        } finally {
            app.stop();
        }
    }

    @Test
    @DisplayName("/api/skills/toggle should only affect global workspace skills")
    void togglesGlobalWorkspaceSkill() throws Exception {
        Path skillsDir = tempDir.resolve("workspace").resolve("skills");
        writeSkill(skillsDir.resolve("alpha"), "alpha", "Alpha");

        SkillsHandler handler = new SkillsHandler(skillsDir);
        int port = freePort();
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);
        app.get("/api/skills", handler::getSkills);
        app.put("/api/skills/toggle", handler::toggleSkill);

        try {
            app.start("127.0.0.1", port);
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + port;

            HttpResponse<String> toggle = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/skills/toggle"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"alpha\",\"enabled\":false}"))
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, toggle.statusCode());
            JSONObject toggled = JSON.parseObject(toggle.body());
            assertTrue(toggled.getBooleanValue("ok"));
            assertEquals("global", toggled.getString("scope"));
            assertEquals("workspace", toggled.getString("source"));
            assertFalse(toggled.getBooleanValue("enabled"));

            HttpResponse<String> list = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/skills")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JSONObject skill = JSON.parseArray(list.body()).getJSONObject(0);
            assertFalse(skill.getBooleanValue("enabled"));

            HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/skills/toggle"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"terminal\",\"enabled\":false}"))
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, missing.statusCode(), "synthetic builtin tool names are not global workspace skills");
        } finally {
            app.stop();
        }
    }

    private static void writeSkill(Path dir, String name, String description) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + description + "\n---\n\n# " + name + "\n");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
