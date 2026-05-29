package fr.euclesia.mcarchipelago.protocol;

import com.google.gson.JsonObject;
import org.jspecify.annotations.NonNull;

public record APVersion(int major, int minor, int build) {
    public static final APVersion V0_6_0 = new APVersion(0, 6, 0);

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("major", major);
        json.addProperty("minor", minor);
        json.addProperty("build", build);
        json.addProperty("class", "Version");
        return json;
    }
}
