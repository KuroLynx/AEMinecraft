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
import fr.euclesia.mcarchipelago.server.gameplay.RootAdvancementService;
import fr.euclesia.mcarchipelago.server.gameplay.StartDimensionService;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.service.DeathLinkService;
import net.minecraft.server.MinecraftServer;

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

        // Rebuild the Archipelago goal tiles (advancements X/Y + bosses M/N) so they show native
        // progress. Done before the reload below so clients receive the rebuilt definitions, and
        // before the scan so already-completed advancements award their criteria.
        MinecraftServer server = AEMServerRuntime.server();
        if (server != null) {
            server.execute(() -> RootAdvancementService.rebuild(server, slotData));
        }

        // Re-evaluate advancement visibility now that the active-location set is known, so
        // non-check advancements disappear from the screen (they were revealed pre-connect), and
        // resync the rebuilt root definition to clients.
        AdvancementBridge.reloadOnlinePlayers();
        // Re-fire completion for already-done advancements: re-sends their checks and awards their
        // tab-root criteria.
        AdvancementBridge.scanOnlinePlayers();
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
