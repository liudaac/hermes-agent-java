package com.nousresearch.hermes.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * JSON utility class using Fastjson2.
 * Replaces Jackson ObjectMapper functionality.
 */
public class JsonUtils {

    /**
     * Parse JSON string to object.
     */
    public static <T> T parseObject(String json, Class<T> clazz) {
        return JSON.parseObject(json, clazz);
    }

    /**
     * Parse JSON string to object with type reference.
     */
    public static <T> T parseObject(String json, TypeReference<T> typeReference) {
        return JSON.parseObject(json, typeReference);
    }

    /**
     * Parse JSON string to JSONObject.
     */
    public static JSONObject parseObject(String json) {
        return JSON.parseObject(json);
    }

    /**
     * Parse JSON string to JSONArray.
     */
    public static JSONArray parseArray(String json) {
        return JSON.parseArray(json);
    }

    /**
     * Parse JSON string to List.
     */
    public static <T> List<T> parseArray(String json, Class<T> clazz) {
        return JSON.parseArray(json, clazz);
    }

    /**
     * Convert object to JSON string.
     */
    public static String toJSONString(Object obj) {
        return JSON.toJSONString(obj);
    }

    /**
     * Convert object to pretty JSON string.
     */
    public static String toJSONStringPretty(Object obj) {
        return JSON.toJSONString(obj, true);
    }

    /**
     * Convert object to JSONObject.
     */
    public static JSONObject toJSONObject(Object obj) {
        return (JSONObject) JSON.toJSON(obj);
    }

    /**
     * Convert object to JSONArray.
     */
    public static JSONArray toJSONArray(Object obj) {
        return (JSONArray) JSON.toJSON(obj);
    }

    /**
     * Get value from JSONObject as String.
     */
    public static String getString(JSONObject json, String key) {
        return json.getString(key);
    }

    /**
     * Get value from JSONObject as String with default.
     */
    public static String getString(JSONObject json, String key, String defaultValue) {
        String value = json.getString(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Get value from JSONObject as int.
     */
    public static int getInt(JSONObject json, String key) {
        return json.getIntValue(key);
    }

    /**
     * Get value from JSONObject as int with default.
     */
    public static int getInt(JSONObject json, String key, int defaultValue) {
        return json.getIntValue(key, defaultValue);
    }

    /**
     * Get value from JSONObject as boolean.
     */
    public static boolean getBoolean(JSONObject json, String key) {
        return json.getBooleanValue(key);
    }

    /**
     * Get value from JSONObject as boolean with default.
     */
    public static boolean getBoolean(JSONObject json, String key, boolean defaultValue) {
        return json.getBooleanValue(key, defaultValue);
    }

    /**
     * Get value from JSONObject as JSONObject.
     */
    public static JSONObject getJSONObject(JSONObject json, String key) {
        return json.getJSONObject(key);
    }

    /**
     * Get value from JSONObject as JSONArray.
     */
    public static JSONArray getJSONArray(JSONObject json, String key) {
        return json.getJSONArray(key);
    }

    /**
     * Check if JSONObject has key.
     */
    public static boolean hasKey(JSONObject json, String key) {
        return json.containsKey(key);
    }

    /**
     * Create a new JSONObject.
     */
    public static JSONObject createObject() {
        return new JSONObject();
    }

    /**
     * Create a new JSONArray.
     */
    public static JSONArray createArray() {
        return new JSONArray();
    }

    /**
     * Convert Map to JSONObject.
     */
    public static JSONObject fromMap(Map<String, Object> map) {
        return new JSONObject(map);
    }

    /**
     * Convert JSONObject to Map.
     */
    public static Map<String, Object> toMap(JSONObject json) {
        return json.toJavaObject(new TypeReference<Map<String, Object>>() {});
    }
}
