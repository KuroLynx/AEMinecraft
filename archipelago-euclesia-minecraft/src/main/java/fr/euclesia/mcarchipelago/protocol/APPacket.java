package fr.euclesia.mcarchipelago.protocol;

import com.google.gson.JsonObject;

public interface APPacket {
    APCommand command();

    JsonObject toJson();
}
