package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APClientStatus;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APPacket;

public record StatusUpdatePacket(APClientStatus status) implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.STATUS_UPDATE;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.addProperty("status", status.code());
        return json;
    }
}
