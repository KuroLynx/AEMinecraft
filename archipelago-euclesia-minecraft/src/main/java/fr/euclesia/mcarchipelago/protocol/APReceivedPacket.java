package fr.euclesia.mcarchipelago.protocol;

import com.google.gson.JsonObject;

public record APReceivedPacket(APCommand command, JsonObject payload) {
}
