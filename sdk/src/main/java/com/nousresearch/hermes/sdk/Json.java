package com.nousresearch.hermes.sdk;

/**
 * Minimal JSON parser - zero external dependencies.
 * Only supports what HermesClient needs: object get(key) -> String/long/boolean.
 */
final class Json {
    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
        this.pos = 0;
    }

    static String getString(String json, String key) {
        if (json == null || key == null) return null;
        var p = new Json(json);
        return p.findKey(key);
    }

    static long getLong(String json, String key) {
        String v = getString(json, key);
        if (v == null) return 0;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return 0; }
    }

    static boolean getBoolean(String json, String key) {
        String v = getString(json, key);
        return "true".equals(v);
    }

    /**
     * Extract a nested object as a raw JSON string.
     * e.g. getObject(json, "plan") returns the {...} substring for the "plan" key.
     */
    static String getObject(String json, String key) {
        if (json == null || key == null) return null;
        var p = new Json(json);
        return p.findKeyObject(key);
    }

    private String findKey(String target) {
        // Search for "target" : "value" or "target" : number or "target" : true/false
        String quoted = "\"" + target + "\"";
        int idx = src.indexOf(quoted, pos);
        if (idx < 0) return null;
        pos = idx + quoted.length();
        // Skip whitespace and colon
        pos = skipWsColon(pos);
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        if (c == '"') {
            // String value (with escape unescaping)
            pos++;
            int start = pos;
            var sb = new StringBuilder();
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (ch == '\\') {
                    if (pos + 1 < src.length()) {
                        char next = src.charAt(pos + 1);
                        switch (next) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case '/' -> sb.append('/');
                            default -> sb.append(next);
                        }
                        pos += 2;
                        continue;
                    }
                }
                if (ch == '"') break;
                sb.append(ch);
                pos++;
            }
            // Skip closing quote
            if (pos < src.length()) pos++;
            return sb.toString();
        } else if (c == 't' || c == 'f') {
            // boolean
            if (src.startsWith("true", pos)) { pos += 4; return "true"; }
            if (src.startsWith("false", pos)) { pos += 5; return "false"; }
            return null;
        } else if (c == 'n') {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            return null;
        } else {
            // number
            int start = pos;
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) break;
                pos++;
            }
            return src.substring(start, pos).trim();
        }
    }

    private String findKeyObject(String target) {
        String quoted = "\"" + target + "\"";
        int idx = src.indexOf(quoted, pos);
        if (idx < 0) return null;
        pos = idx + quoted.length();
        pos = skipWsColon(pos);
        if (pos >= src.length() || src.charAt(pos) != '{') return null;
        int start = pos;
        int depth = 0;
        boolean inString = false;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (ch == '\\') { pos += 2; continue; }
            if (ch == '"') inString = !inString;
            if (!inString) {
                if (ch == '{') depth++;
                if (ch == '}') { depth--; if (depth == 0) { pos++; return src.substring(start, pos); } }
            }
            pos++;
        }
        return null;
    }

    private int skipWsColon(int from) {
        while (from < src.length()) {
            char c = src.charAt(from);
            if (Character.isWhitespace(c)) { from++; continue; }
            if (c == ':') { from++; break; }
            break;
        }
        while (from < src.length() && Character.isWhitespace(src.charAt(from))) from++;
        return from;
    }

    /**
     * Escape a string for JSON.
     */
    static String esc(String s) {
        if (s == null) return "";
        var sb = new StringBuilder(s.length() + 10);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
