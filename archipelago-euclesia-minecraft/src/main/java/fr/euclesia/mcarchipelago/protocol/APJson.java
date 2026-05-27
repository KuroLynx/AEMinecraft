package fr.euclesia.mcarchipelago.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class APJson {
    private APJson() {}

    public static JsonArray strings(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    public static JsonArray longs(Collection<Long> values) {
        JsonArray array = new JsonArray();
        for (Long value : values) {
            array.add(value);
        }
        return array;
    }

    public static JsonArray elements(Collection<? extends JsonElement> values) {
        JsonArray array = new JsonArray();
        for (JsonElement value : values) {
            array.add(value);
        }
        return array;
    }

    public static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
    }

    public static int getInt(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }

    public static long getLong(JsonObject json, String key, long fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsLong() : fallback;
    }

    public static String getString(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    public static List<String> stringList(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (value.isJsonPrimitive()) {
                values.add(value.getAsString());
            }
        }
        return List.copyOf(values);
    }

    public static Set<String> stringSet(JsonObject json, String key) {
        return Set.copyOf(stringList(json, key));
    }

    public static Map<String, Long> stringLongMap(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }

        Map<String, Long> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                values.put(entry.getKey(), entry.getValue().getAsLong());
            }
        }
        return Map.copyOf(values);
    }

    public static Map<Long, String> longStringMap(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }

        Map<Long, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                values.put(Long.parseLong(entry.getKey()), entry.getValue().getAsString());
            }
        }
        return Map.copyOf(values);
    }

    public static Set<Long> longSet(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray()) {
            return Set.of();
        }

        Set<Long> values = new LinkedHashSet<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (value.isJsonPrimitive()) {
                values.add(value.getAsLong());
            }
        }
        return Set.copyOf(values);
    }
}
