package fr.euclesia.mcarchipelago.server.ap;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.protocol.APBounceType;
import fr.euclesia.mcarchipelago.protocol.APItemsHandling;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.ConnectUpdatePacket;
import fr.euclesia.mcarchipelago.server.gameplay.AdvancementBridge;
import fr.euclesia.mcarchipelago.server.gameplay.StartDimensionService;
import fr.euclesia.mcarchipelago.server.service.DeathLinkService;

import java.util.List;

public final class ArchipelagoGameplayListener implements APEventListener {
    @Override
    public void onConnected(ArchipelagoClient client, APReceivedPacket packet) {
        APSlotData slotData = client.state().parsedSlotData();
        if (slotData.deathLink()) {
            client.send(new ConnectUpdatePacket(APBounceType.tags(APBounceType.DEATH_LINK), APItemsHandling.ALL));
        }

        // Covers the connect-after-join path (/archipelago connect): now that the slot data is
        // known, relocate any online player who still needs their start dimension applied.
        StartDimensionService.applyToOnlinePlayers();

        AdvancementBridge.scanOnlinePlayers();
        // Re-evaluate advancement visibility now that the active-location set is known, so
        // non-check advancements disappear from the screen (they were revealed pre-connect).
        AdvancementBridge.reloadOnlinePlayers();
    }

    @Override
    public void onBounced(ArchipelagoClient client, APReceivedPacket packet) {
        List<String> tags = APJson.stringList(packet.payload(), "tags");
        if (!tags.contains(APBounceType.DEATH_LINK.tag())) {
            return;
        }

        JsonObject data = packet.payload().has("data") && packet.payload().get("data").isJsonObject()
                ? packet.payload().getAsJsonObject("data")
                : new JsonObject();
        DeathLinkService.applyRemote(APJson.getString(data, "cause", "A linked player died"));
    }
}
