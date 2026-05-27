package fr.euclesia.mcarchipelago.protocol.packet.inbound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;

import java.util.ArrayList;
import java.util.List;

public record ReceivedItemsPacket(int index, List<APNetworkItem> items) {
    public static ReceivedItemsPacket from(APReceivedPacket packet) {
        JsonObject payload = packet.payload();
        List<APNetworkItem> items = new ArrayList<>();

        if (payload.has("items") && payload.get("items").isJsonArray()) {
            JsonArray array = payload.getAsJsonArray("items");
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    items.add(APNetworkItem.fromJson(element.getAsJsonObject()));
                }
            }
        }

        return new ReceivedItemsPacket(APJson.getInt(payload, "index", 0), List.copyOf(items));
    }
}
