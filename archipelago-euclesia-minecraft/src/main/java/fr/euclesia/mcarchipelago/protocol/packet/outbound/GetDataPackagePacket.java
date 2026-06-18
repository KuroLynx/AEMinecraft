package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;

import java.util.List;

public record GetDataPackagePacket(List<String> games) implements APPacket {
    /**
     * Requests every game's data package. Needed so chat can resolve item/location names for other
     * games' slots, not just Minecraft's.
     */
    public GetDataPackagePacket() {
        this(List.of());
    }

    @Override
    public APCommand command() {
        return APCommand.GET_DATA_PACKAGE;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        // Per the AP protocol, omitting "games" returns all games; sending it scopes the response to
        // the listed games. An empty list means "all", so only include the field when scoping.
        if (games != null && !games.isEmpty()) {
            json.add("games", APJson.strings(games));
        }
        return json;
    }
}
