package fr.euclesia.mcarchipelago.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.Collection;

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
}
