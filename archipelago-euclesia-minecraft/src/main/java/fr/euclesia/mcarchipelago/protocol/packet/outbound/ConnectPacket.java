package fr.euclesia.mcarchipelago.protocol.packet.outbound;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APCommand;
import fr.euclesia.mcarchipelago.protocol.APItemsHandling;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APPacket;
import fr.euclesia.mcarchipelago.protocol.APVersion;

import java.util.List;

public record ConnectPacket(
        String game,
        String name,
        String password,
        APVersion version,
        int itemsHandling,
        List<String> tags,
        String uuid
) implements APPacket {
    public ConnectPacket(String name, String password, String uuid) {
        this("Minecraft", name, password, APVersion.V0_6_0, APItemsHandling.ALL, List.of(), uuid);
    }

    @Override
    public APCommand command() {
        return APCommand.CONNECT;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cmd", command().getCommandName());
        json.addProperty("game", game);
        json.addProperty("name", name);
        json.addProperty("password", password);
        json.add("version", version.toJson());
        json.addProperty("items_handling", itemsHandling);
        json.add("tags", APJson.strings(tags));
        json.addProperty("uuid", uuid);
        return json;
    }
}
