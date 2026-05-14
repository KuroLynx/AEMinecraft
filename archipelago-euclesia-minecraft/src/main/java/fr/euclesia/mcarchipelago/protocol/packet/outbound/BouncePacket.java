package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;

import java.util.List;

public record BouncePacket(List<String> tags, JsonObject data) implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.BOUNCE;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.add("tags", APJson.strings(tags));
        json.add("data", data);
        return json;
    }
}
