package fr.euclesia.mcarchipelago.engine.ap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.protocol.APBounceType;
import fr.euclesia.mcarchipelago.protocol.APDataStorageOperation;
import fr.euclesia.mcarchipelago.protocol.APHintMode;

import java.util.Collection;
import java.util.List;

public interface ArchipelagoGateway {
    void checkLocations(Collection<Long> locations);

    boolean checkLocation(String gameId);

    boolean checkTrackedMob(String mobGameId);

    void scoutLocations(Collection<Long> locations, APHintMode hintMode);

    void markGoalReached();

    void say(String text);

    void getDataPackage();

    void getDataStorage(List<String> keys);

    void setDataStorage(String key, JsonElement defaultValue, boolean wantReply, APDataStorageOperation operation, JsonElement value);

    void subscribeDataStorage(List<String> keys);

    void bounce(APBounceType type, JsonObject data);

    void bounce(List<String> tags, JsonObject data);
}
