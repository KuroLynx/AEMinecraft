package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APDataStorageOperation;

public record SetOperation(APDataStorageOperation operation, JsonElement value) {
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("operation", operation.id());
        json.add("value", value);
        return json;
    }
}
