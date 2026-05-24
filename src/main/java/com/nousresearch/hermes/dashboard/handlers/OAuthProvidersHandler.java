package com.nousresearch.hermes.dashboard.handlers;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for Dashboard OAuth provider status endpoints.
 *
 * Java Hermes does not currently own an OAuth token exchange implementation for
 * provider CLIs.  These routes expose explicit external-provider status so the UI
 * can render safely, while interactive OAuth actions return structured 501s
 * instead of 404s or fake success.
 */
public class OAuthProvidersHandler {
    private static final Logger logger = LoggerFactory.getLogger(OAuthProvidersHandler.class);

    private final List<ProviderDefinition> providers;

    public OAuthProvidersHandler() {
        this.providers = List.of(
            new ProviderDefinition(
                "claude-code",
                "Claude Code",
                "external",
                "claude /login",
                "https://docs.anthropic.com/en/docs/claude-code",
                List.of(
                    Path.of(System.getProperty("user.home"), ".claude"),
                    Path.of(System.getProperty("user.home"), ".config", "claude")
                )
            ),
            new ProviderDefinition(
                "qwen-code",
                "Qwen Code",
                "external",
                "qwen auth login",
                "https://github.com/QwenLM/qwen-code",
                List.of(
                    Path.of(System.getProperty("user.home"), ".qwen"),
                    Path.of(System.getProperty("user.home"), ".config", "qwen")
                )
            )
        );
    }

    /** GET /api/providers/oauth */
    public void listProviders(Context ctx) {
        try {
            ctx.json(Map.of("providers", providers.stream().map(this::providerToMap).toList()));
        } catch (Exception e) {
            logger.error("Error listing OAuth providers: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /** DELETE /api/providers/oauth/{providerId} */
    public void disconnectProvider(Context ctx) {
        String providerId = ctx.pathParam("providerId");
        ProviderDefinition provider = findProvider(providerId);
        if (provider == null) {
            ctx.status(404).json(Map.of("error", "OAuth provider not found: " + providerId));
            return;
        }
        unsupported(ctx, providerId, "Provider OAuth credentials are managed externally by the CLI and cannot be disconnected from the dashboard yet.");
    }

    /** POST /api/providers/oauth/{providerId}/start */
    public void startLogin(Context ctx) {
        String providerId = ctx.pathParam("providerId");
        ProviderDefinition provider = findProvider(providerId);
        if (provider == null) {
            ctx.status(404).json(Map.of("error", "OAuth provider not found: " + providerId));
            return;
        }
        unsupported(ctx, providerId, "OAuth login is not wired in Java Hermes yet. Use the provider CLI command instead: " + provider.cliCommand());
    }

    /** POST /api/providers/oauth/{providerId}/submit */
    public void submitCode(Context ctx) {
        String providerId = ctx.pathParam("providerId");
        ProviderDefinition provider = findProvider(providerId);
        if (provider == null) {
            ctx.status(404).json(Map.of("error", "OAuth provider not found: " + providerId));
            return;
        }
        unsupported(ctx, providerId, "OAuth code exchange is not wired in Java Hermes yet. Use the provider CLI command instead: " + provider.cliCommand());
    }

    /** GET /api/providers/oauth/{providerId}/poll/{sessionId} */
    public void pollSession(Context ctx) {
        String providerId = ctx.pathParam("providerId");
        String sessionId = ctx.pathParam("sessionId");
        ProviderDefinition provider = findProvider(providerId);
        if (provider == null) {
            ctx.status(404).json(Map.of("error", "OAuth provider not found: " + providerId));
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session_id", sessionId);
        response.put("status", "error");
        response.put("error_message", "OAuth polling is not wired in Java Hermes yet. Use the provider CLI command instead: " + provider.cliCommand());
        response.put("expires_at", null);
        ctx.status(501).json(response);
    }

    /** DELETE /api/providers/oauth/sessions/{sessionId} */
    public void cancelSession(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        ctx.status(501).json(Map.of(
            "ok", false,
            "unsupported", true,
            "session_id", sessionId,
            "message", "OAuth sessions are not managed by Java Hermes dashboard yet."
        ));
    }

    private ProviderDefinition findProvider(String providerId) {
        return providers.stream()
            .filter(provider -> provider.id().equals(providerId))
            .findFirst()
            .orElse(null);
    }

    private Map<String, Object> providerToMap(ProviderDefinition provider) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", provider.id());
        map.put("name", provider.name());
        map.put("flow", provider.flow());
        map.put("cli_command", provider.cliCommand());
        map.put("docs_url", provider.docsUrl());
        map.put("status", statusFor(provider));
        return map;
    }

    private Map<String, Object> statusFor(ProviderDefinition provider) {
        Path detectedPath = provider.statusPaths().stream()
            .filter(Files::exists)
            .findFirst()
            .orElse(null);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("logged_in", detectedPath != null);
        status.put("source", detectedPath != null ? detectedPath.toString() : null);
        status.put("source_label", detectedPath != null ? "CLI config" : null);
        status.put("token_preview", null);
        status.put("expires_at", null);
        status.put("has_refresh_token", false);
        status.put("last_refresh", null);
        status.put("error", null);
        return status;
    }

    private void unsupported(Context ctx, String providerId, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("unsupported", true);
        response.put("provider", providerId);
        response.put("status", "error");
        response.put("message", message);
        response.put("updated_at", Instant.now().toString());
        ctx.status(501).json(response);
    }

    record ProviderDefinition(
        String id,
        String name,
        String flow,
        String cliCommand,
        String docsUrl,
        List<Path> statusPaths
    ) {}
}
