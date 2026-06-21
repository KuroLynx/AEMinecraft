package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APPacket;

/** Asks the server to resend every received item (a full {@code ReceivedItems} resync, index 0). */
public record SyncPacket() implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.SYNC;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        return json;
    }
}
