package fr.euclesia.mcarchipelago.engine.ap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

import java.util.Collection;
import java.util.List;

public final class APArchipelagoGateway implements ArchipelagoGateway {
    private final ArchipelagoClient client;

    public APArchipelagoGateway(ArchipelagoClient client) {
        this.client = client;
    }

    @Override
    public void checkLocations(Collection<Long> locations) {
        client.send(new LocationChecksPacket(locations));
    }

    @Override
    public void scoutLocations(Collection<Long> locations, APHintMode hintMode) {
        client.send(new LocationScoutsPacket(locations, hintMode));
    }

    @Override
    public void markGoalReached() {
        client.send(new StatusUpdatePacket(APClientStatus.CLIENT_GOAL));
    }

    @Override
    public void say(String text) {
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
        client.send(new BouncePacket(tags, data));
    }
}
