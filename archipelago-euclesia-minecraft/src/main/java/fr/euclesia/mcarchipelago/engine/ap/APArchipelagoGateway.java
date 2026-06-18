package fr.euclesia.mcarchipelago.engine.ap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.APBounceType;
import fr.euclesia.mcarchipelago.protocol.APClientStatus;
import fr.euclesia.mcarchipelago.protocol.APDataStorageOperation;
import fr.euclesia.mcarchipelago.protocol.APHintMode;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.BouncePacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.GetDataPackagePacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.GetPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.LocationChecksPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.LocationScoutsPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.SayPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.SetNotifyPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.SetOperation;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.SetPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.StatusUpdatePacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class APArchipelagoGateway implements ArchipelagoGateway {
    private final ArchipelagoClient client;

    public APArchipelagoGateway(ArchipelagoClient client) {
        this.client = client;
    }

    @Override
    public void checkLocations(Collection<Long> locations) {
        AEMDebug.log("gateway.checkLocations ids={}", locations);
        client.send(new LocationChecksPacket(locations));
    }

    @Override
    public boolean checkLocation(String gameId) {
        return client.registries().apLocations().idForGameId(gameId)
                .map(locationId -> {
                    checkLocations(List.of(locationId));
                    return true;
                })
                .orElseGet(() -> {
                    AEMDebug.log("gateway.checkLocation unresolved gameId='{}' (not an active location this seed)", gameId);
                    return false;
                });
    }

    @Override
    public int checkLocationsByGameId(Collection<String> gameIds) {
        var apLocations = client.registries().apLocations();
        List<Long> locationIds = new ArrayList<>();
        for (String gameId : gameIds) {
            apLocations.idForGameId(gameId).ifPresent(locationIds::add);
        }
        AEMDebug.log("gateway.checkLocationsByGameId resolved {}/{} ids", locationIds.size(), gameIds.size());
        if (!locationIds.isEmpty()) {
            checkLocations(locationIds);
        }
        return locationIds.size();
    }

    @Override
    public boolean checkTrackedMob(String mobGameId) {
        return client.registries().apMobs().trackedLocationId(mobGameId)
                .map(locationId -> {
                    AEMDebug.log("gateway.checkTrackedMob '{}' -> location {}", mobGameId, locationId);
                    checkLocations(List.of(locationId));
                    return true;
                })
                .orElse(false);
    }

    @Override
    public void scoutLocations(Collection<Long> locations, APHintMode hintMode) {
        AEMDebug.log("gateway.scoutLocations ids={} mode={}", locations, hintMode);
        client.send(new LocationScoutsPacket(locations, hintMode));
    }

    @Override
    public void markGoalReached() {
        AEMDebug.log("gateway.markGoalReached -> CLIENT_GOAL");
        client.send(new StatusUpdatePacket(APClientStatus.CLIENT_GOAL));
    }

    @Override
    public void say(String text) {
        AEMDebug.log("gateway.say '{}'", text);
        client.send(new SayPacket(text));
    }

    @Override
    public void getDataPackage() {
        client.send(new GetDataPackagePacket());
    }

    @Override
    public void getDataStorage(List<String> keys) {
        client.send(new GetPacket(keys));
    }

    @Override
    public void setDataStorage(String key, JsonElement defaultValue, boolean wantReply, APDataStorageOperation operation, JsonElement value) {
        client.send(new SetPacket(key, defaultValue, wantReply, List.of(new SetOperation(operation, value))));
    }

    @Override
    public void subscribeDataStorage(List<String> keys) {
        client.send(new SetNotifyPacket(keys));
    }

    @Override
    public void bounce(APBounceType type, JsonObject data) {
        bounce(APBounceType.tags(type), data);
    }

    @Override
    public void bounce(List<String> tags, JsonObject data) {
        AEMDebug.log("gateway.bounce tags={} data={}", tags, data);
        client.send(new BouncePacket(tags, data));
    }
}
