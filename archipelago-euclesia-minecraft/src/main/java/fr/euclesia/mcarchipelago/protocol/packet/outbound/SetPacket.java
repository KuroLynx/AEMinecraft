package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;

import java.util.List;

public record SetPacket(String key, JsonElement defaultValue, boolean wantReply, List<SetOperation> operations) implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.SET;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.addProperty("key", key);
        json.add("default", defaultValue);
        json.addProperty("want_reply", wantReply);
        json.add("operations", APJson.elements(operations.stream().map(SetOperation::toJson).toList()));
        return json;
    }
}
