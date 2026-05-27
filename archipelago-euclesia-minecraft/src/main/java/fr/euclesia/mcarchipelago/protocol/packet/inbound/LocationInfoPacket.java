package fr.euclesia.mcarchipelago.protocol.packet.inbound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;

import java.util.ArrayList;
import java.util.List;

public record LocationInfoPacket(List<APNetworkItem> locations) {
    public static LocationInfoPacket from(APReceivedPacket packet) {
        JsonObject payload = packet.payload();
        List<APNetworkItem> locations = new ArrayList<>();

        if (payload.has("locations") && payload.get("locations").isJsonArray()) {
            JsonArray array = payload.getAsJsonArray("locations");
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    locations.add(APNetworkItem.fromJson(element.getAsJsonObject()));
                }
            }
        }

        return new LocationInfoPacket(List.copyOf(locations));
    }
}
