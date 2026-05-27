package fr.euclesia.mcarchipelago.protocol.packet.inbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APItemClassification;
import fr.euclesia.mcarchipelago.protocol.APJson;

public record APNetworkItem(long itemId, long locationId, int player, APItemClassification classification) {
    public static APNetworkItem fromJson(JsonObject json) {
        return new APNetworkItem(
                APJson.getLong(json, "item", -1),
                APJson.getLong(json, "location", -1),
                APJson.getInt(json, "player", -1),
                APItemClassification.fromFlag(APJson.getInt(json, "flags", 0))
        );
    }
}
