package fr.euclesia.mcarchipelago.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class APPacketCodec {
    private static final Gson GSON = new Gson();

    private APPacketCodec() {}

    public static String encodeWire(APPacket packet) {
        return encodeWire(List.of(packet));
    }

    public static String encodeWire(Collection<? extends APPacket> packets) {
        JsonArray array = new JsonArray();
        for (APPacket packet : packets) {
            array.add(packet.toJson());
        }
        return GSON.toJson(array);
    }

    public static List<APReceivedPacket> decodeWire(String message) {
        JsonElement root = JsonParser.parseString(message);
        List<APReceivedPacket> packets = new ArrayList<>();

        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                packets.add(decodeOne(element));
            }
            return packets;
        }

        packets.add(decodeOne(root));
        return packets;
    }

    private static APReceivedPacket decodeOne(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new APProtocolException("Expected AP packet object, got: " + element);
        }

        JsonObject payload = element.getAsJsonObject();
        JsonElement cmdElement = payload.get("cmd");
        if (cmdElement == null || !cmdElement.isJsonPrimitive()) {
            throw new APProtocolException("AP packet is missing a string cmd field: " + payload);
        }

        APCommand command = APCommand.fromString(cmdElement.getAsString());
        if (command == null) {
            throw new APProtocolException("Unknown AP command: " + cmdElement.getAsString());
        }

        return new APReceivedPacket(command, payload);
    }
}
