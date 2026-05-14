package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;

import java.util.Collection;

public record LocationChecksPacket(Collection<Long> locations) implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.LOCATION_CHECKS;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.add("locations", APJson.longs(locations));
        return json;
    }
}
