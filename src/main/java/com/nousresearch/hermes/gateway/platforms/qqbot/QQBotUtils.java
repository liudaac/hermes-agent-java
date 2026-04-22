package com.nousresearch.hermes.gateway.platforms.qqbot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QQBot shared utilities — User-Agent, HTTP helpers, config coercion.
 * Mirrors Python gateway/platforms/qqbot/utils.py
 */
public class QQBotUtils {
    
    public static final String QQBOT_VERSION = "1.0.0";
    
    /**
     * Get Hermes version.
     * 
     * @return Hermes version string
     */
    public static String getHermesVersion() {
        // Try to get from package, fallback to dev
        try {
            Package pkg = QQBotUtils.class.getPackage();
            String version = pkg != null ? pkg.getImplementationVersion() : null;
            return version != null ? version : "dev";
        } catch (Exception e) {
            return "dev";
        }
    }
    
    /**
     * Build a descriptive User-Agent string.
     * 
     * Format: QQBotAdapter/<qqbot_version> (Java/<java_version>; <os>; Hermes/<hermes_version>)
     * 
     * Example: QQBotAdapter/1.0.0 (Java/21.0.1; Linux; Hermes/0.9.0)
     * 
     * @return User-Agent string
     */
    public static String buildUserAgent() {
        String javaVersion = System.getProperty("java.version");
        String osName = System.getProperty("os.name").toLowerCase();
        String hermesVersion = getHermesVersion();
        
        return String.format(
            "QQBotAdapter/%s (Java/%s; %s; Hermes/%s)",
            QQBOT_VERSION,
            javaVersion,
            osName,
            hermesVersion
        );
    }
    
    /**
     * Return standard HTTP headers for QQBot API requests.
     * 
     * Includes Content-Type, Accept, and a dynamic User-Agent.
     * 
     * @return Standard headers map
     */
    public static Map<String, String> getApiHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", buildUserAgent());
        return headers;
    }
    
    /**
     * Return standard HTTP headers with authorization.
     * 
     * @param appId Bot app ID
     * @param token Access token
     * @return Headers with authorization
     */
    public static Map<String, String> getAuthorizedHeaders(String appId, String token) {
        Map<String, String> headers = getApiHeaders();
        headers.put("Authorization", String.format("QQBot %s.%s", appId, token));
        return headers;
    }
    
    /**
     * Coerce config values into a trimmed string list.
     * 
     * Accepts comma-separated strings, lists, or single values.
     * 
     * @param value The value to coerce
     * @return List of trimmed strings
     */
    public static List<String> coerceList(Object value) {
        List<String> result = new ArrayList<>();
        
        if (value == null) {
            return result;
        }
        
        if (value instanceof String) {
            String str = (String) value;
            if (str.trim().isEmpty()) {
                return result;
            }
            String[] parts = str.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) {
                    String trimmed = item.toString().trim();
                    if (!trimmed.isEmpty()) {
                        result.add(trimmed);
                    }
                }
            }
        } else if (value instanceof String[]) {
            for (String item : (String[]) value) {
                if (item != null) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        result.add(trimmed);
                    }
                }
            }
        } else {
            String trimmed = value.toString().trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        
        return result;
    }
    
    /**
     * Truncate text to maximum length.
     * 
     * @param text Text to truncate
     * @param maxLength Maximum length
     * @return Truncated text
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
    
    /**
     * Escape markdown special characters for QQ markdown.
     * 
     * @param text Text to escape
     * @return Escaped text
     */
    public static String escapeMarkdown(String text) {
        if (text == null) {
            return null;
        }
        return text
            .replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace("=", "\\=")
            .replace("|", "\\|")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace(".", "\\.")
            .replace("!", "\\!");
    }
    
    /**
     * Strip markdown formatting from text.
     * 
     * @param text Markdown text
     * @return Plain text
     */
    public static String stripMarkdown(String text) {
        if (text == null) {
            return null;
        }
        
        // Remove code blocks
        text = text.replaceAll("```[\\s\\S]*?```", "");
        text = text.replaceAll("`([^`]+)`", "$1");
        
        // Remove bold and italic
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        text = text.replaceAll("\\*([^*]+)\\*", "$1");
        text = text.replaceAll("__([^_]+)__", "$1");
        text = text.replaceAll("_([^_]+)_", "$1");
        
        // Remove links
        text = text.replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1");
        
        // Remove headers
        text = text.replaceAll("^#{1,6}\\s*", "");
        
        // Remove blockquotes
        text = text.replaceAll("^>\\s*", "");
        
        return text.trim();
    }
    
    /**
     * Check if a string is blank (null, empty, or whitespace only).
     * 
     * @param str String to check
     * @return true if blank
     */
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    /**
     * Check if a string is not blank.
     * 
     * @param str String to check
     * @return true if not blank
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }
}
