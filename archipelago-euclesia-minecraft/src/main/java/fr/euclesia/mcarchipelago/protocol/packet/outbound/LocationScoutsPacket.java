package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APHintMode;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;

import java.util.Collection;

public record LocationScoutsPacket(Collection<Long> locations, APHintMode createAsHint) implements APPacket {
    @Override
    public APCommand command() {
        return APCommand.LOCATION_SCOUTS;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.add("locations", APJson.longs(locations));
        json.addProperty("create_as_hint", createAsHint.code());
        return json;
    }
}
