package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;

import java.util.List;

public record GetDataPackagePacket(List<String> games) implements APPacket {
    public GetDataPackagePacket() {
        this(List.of("Minecraft"));
    }

    @Override
    public APCommand command() {
        return APCommand.GET_DATA_PACKAGE;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.add("games", APJson.strings(games));
        return json;
    }
}
