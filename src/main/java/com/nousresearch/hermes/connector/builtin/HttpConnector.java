package com.nousresearch.hermes.connector.builtin;

import com.nousresearch.hermes.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * E3: Generic HTTP Connector.
 *
 * <p>A universal connector that can call any HTTP API. Configured with
 * a base URL and optional API key. Supports GET, POST, PUT, DELETE.</p>
 *
 * <p>Operations:</p>
 * <ul>
 *   <li>get - HTTP GET with path + query params</li>
 *   <li>post - HTTP POST with JSON body</li>
 *   <li>put - HTTP PUT with JSON body</li>
 *   <li>delete - HTTP DELETE</li>
 * </ul>
 */
public class HttpConnector implements Connector {

    private static final Logger logger = LoggerFactory.getLogger(HttpConnector.class);

    private String baseUrl;
    private String apiKey;
    private String apiHeader = "Authorization";
    private String apiPrefix = "Bearer ";
    private final HttpClient httpClient;

    public HttpConnector() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    @Override
    public String getName() { return "http"; }

    @Override
    public String getLabel() { return "HTTP API"; }

    @Override
    public String getDescription() {
        return "Universal HTTP API connector. Supports GET/POST/PUT/DELETE with configurable base URL and auth.";
    }

    @Override
    public boolean testConnection() {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> execute(String operation, Map<String, Object> params) {
        String path = (String) params.getOrDefault("path", "");
        String method = operation.toUpperCase();
        String url = baseUrl + (path.startsWith("/") ? path : "/" + path);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30));

            // Add auth header
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header(apiHeader, apiPrefix + apiKey);
            }

            // Add query params for GET
            if ("GET".equals(method) && params.containsKey("query")) {
                @SuppressWarnings("unchecked")
                Map<String, String> query = (Map<String, String>) params.get("query");
                if (!query.isEmpty()) {
                    StringBuilder qs = new StringBuilder("?");
                    boolean first = true;
                    for (var e : query.entrySet()) {
                        if (!first) qs.append("&");
                        first = false;
                        qs.append(e.getKey()).append("=").append(e.getValue());
                    }
                    url += qs;
                    builder.uri(URI.create(url));
                }
            }

            // Add body for POST/PUT
            if ("POST".equals(method) || "PUT".equals(method)) {
                String body = params.containsKey("body")
                    ? com.alibaba.fastjson2.JSON.toJSONString(params.get("body"))
                    : "{}";
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else if ("DELETE".equals(method)) {
                builder.DELETE();
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statusCode", response.statusCode());
            result.put("body", response.body());
            result.put("success", response.statusCode() >= 200 && response.statusCode() < 300);
            return result;

        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return error;
        }
    }

    @Override
    public List<ConnectorOperation> getSupportedOperations() {
        return List.of(
            new ConnectorOperation("get", "GET Request", "HTTP GET request",
                Map.of("path", Map.of("type", "string"), "query", Map.of("type", "object")),
                Map.of("statusCode", Map.of("type", "integer"), "body", Map.of("type", "string"))),
            new ConnectorOperation("post", "POST Request", "HTTP POST request with JSON body",
                Map.of("path", Map.of("type", "string"), "body", Map.of("type", "object")),
                Map.of("statusCode", Map.of("type", "integer"), "body", Map.of("type", "string"))),
            new ConnectorOperation("put", "PUT Request", "HTTP PUT request with JSON body",
                Map.of("path", Map.of("type", "string"), "body", Map.of("type", "object")),
                Map.of("statusCode", Map.of("type", "integer"), "body", Map.of("type", "string"))),
            new ConnectorOperation("delete", "DELETE Request", "HTTP DELETE request",
                Map.of("path", Map.of("type", "string")),
                Map.of("statusCode", Map.of("type", "integer"), "body", Map.of("type", "string")))
        );
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("baseUrl", Map.of("type", "string", "required", true, "label", "Base URL"));
        schema.put("apiKey", Map.of("type", "string", "required", false, "label", "API Key"));
        schema.put("apiHeader", Map.of("type", "string", "required", false, "default", "Authorization"));
        schema.put("apiPrefix", Map.of("type", "string", "required", false, "default", "Bearer "));
        return schema;
    }

    @Override
    public void configure(Map<String, Object> config) {
        this.baseUrl = (String) config.get("baseUrl");
        this.apiKey = (String) config.get("apiKey");
        if (config.containsKey("apiHeader")) this.apiHeader = (String) config.get("apiHeader");
        if (config.containsKey("apiPrefix")) this.apiPrefix = (String) config.get("apiPrefix");
    }

    @Override
    public boolean isHealthy() {
        return testConnection();
    }
}
