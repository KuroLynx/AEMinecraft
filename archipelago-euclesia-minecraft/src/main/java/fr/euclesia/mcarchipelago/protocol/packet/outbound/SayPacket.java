package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APPacket;

public record SayPacket(String text) implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.SAY;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.addProperty("text", text);
        return json;
    }
}
