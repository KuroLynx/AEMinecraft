package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;

import java.util.List;

public record GetPacket(List<String> keys) implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.GET;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.add("keys", APJson.strings(keys));
        return json;
    }
}
