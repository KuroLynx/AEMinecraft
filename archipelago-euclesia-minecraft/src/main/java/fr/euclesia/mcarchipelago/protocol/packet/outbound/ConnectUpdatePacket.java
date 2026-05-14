package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;

import java.util.List;

public record ConnectUpdatePacket(List<String> tags, int itemsHandling) implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.CONNECT_UPDATE;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.add("tags", APJson.strings(tags));
        json.addProperty("items_handling", itemsHandling);
        return json;
    }
}
