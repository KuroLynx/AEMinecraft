package fr.euclesia.mcarchipelago.server.ap;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.archipelago.DeathLinkPreference;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.archipelago.slot.CompatibilityService;
import fr.euclesia.mcarchipelago.archipelago.slot.ContentVerification;
import fr.euclesia.mcarchipelago.protocol.APBounceType;
import fr.euclesia.mcarchipelago.protocol.APItemsHandling;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;
import fr.euclesia.mcarchipelago.net.APStateSync;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.ConnectUpdatePacket;
import fr.euclesia.mcarchipelago.server.gameplay.SlotReleaseService;
import fr.euclesia.mcarchipelago.server.gameplay.AdvancementBridge;
import fr.euclesia.mcarchipelago.server.gameplay.BacapConfigService;
import fr.euclesia.mcarchipelago.server.gameplay.RootAdvancementService;
import fr.euclesia.mcarchipelago.server.gameplay.StartDimensionService;
import fr.euclesia.mcarchipelago.server.gameplay.TrackerLayoutService;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.service.DeathLinkService;
import net.minecraft.server.MinecraftServer;

import java.util.List;

public final class ArchipelagoGameplayListener implements APEventListener {
    @Override
    public void onConnected(ArchipelagoClient client, APReceivedPacket packet) {
        APSlotData slotData = client.state().parsedSlotData();

        // Compatibility guard. In singleplayer the client pre-flight (ArchipelagoConnectingScreen)
        // already refuses to enter an incompatible world; this covers the paths without that screen
        // (dedicated server, connect-after-join) with a clear server-log error.
        CompatibilityService.Result compat = CompatibilityService.check(slotData.slotDataVersion());
        if (compat.blocking()) {
            AEM.LOGGER.error("[AEM] Incompatible apworld/mod: {} (slot_data_version={}; this mod supports {}..{}).",
                    compat.message().getString(), slotData.slotDataVersion(),
                    CompatibilityService.MIN_SUPPORTED, CompatibilityService.MAX_SUPPORTED);
        }

        // The tag is what makes the room send us other worlds' deaths, so it follows the live setting
        // rather than the slot's option directly: with no override the two are the same thing, and
        // with one (an operator ran /aem deathlink off) a reconnect must not quietly switch receiving
        // back on while sending stays off.
        if (DeathLinkPreference.enabled()) {
            client.send(new ConnectUpdatePacket(APBounceType.tags(APBounceType.DEATH_LINK), APItemsHandling.ALL));
        }

        // The slot is known at last, so everything held back only because it was UNKNOWN can be let
        // go: structures captured blind that this slot does not lock get placed for real, and mobs
        // deferred blind get spawned. Without this the fail-closed gate would be a one-way door.
        SlotReleaseService.releaseUnlockedContent();

        // Covers the connect-after-join path (/archipelago connect): now that the slot data is
        // known, relocate any online player who still needs their start dimension applied.
        StartDimensionService.applyToOnlinePlayers();

        // Rebuild the Archipelago goal tiles (advancements X/Y + bosses M/N) so they show native
        // progress. Done before the reload below so clients receive the rebuilt definitions, and
        // before the scan so already-completed advancements award their criteria.
        MinecraftServer server = AEMServerRuntime.server();
        if (server != null) {
            // Content guard for the paths without the client pre-flight (dedicated server,
            // connect-after-join): a required datapack/mod that's missing or the wrong version can't be
            // blocked from a running world, but log a clear error so the desync is diagnosable.
            ContentVerification.Result content =
                    ContentVerification.verify(slotData.requiredContent(), server.getPackRepository());
            if (!content.ok()) {
                AEM.LOGGER.error("[AEM] Missing/incompatible required content: {}", content.message().getString());
            }

            // Close the gaps the seed leaves in the tracker rows before the reload below sends them:
            // a row's chain breaks at the first tile this seed doesn't use, hiding the rest.
            server.execute(() -> TrackerLayoutService.compact(server));
            server.execute(() -> RootAdvancementService.rebuild(server, slotData));
            // One-time BACAP reward/trophy config, now that the slot data is known (covers
            // connect-after-join). Scheduled on the server thread; no-op if already applied.
            server.execute(() -> BacapConfigService.applyIfNeeded(server));
        }

        // Re-evaluate advancement visibility now that the active-location set is known, so
        // non-check advancements disappear from the screen (they were revealed pre-connect), and
        // resync the rebuilt root definition to clients.
        AdvancementBridge.reloadOnlinePlayers();
        // Re-fire completion for already-done advancements: re-sends their checks and awards their
        // tab-root criteria.
        AdvancementBridge.scanOnlinePlayers();

        // The client tracker runs on this data and has no session of its own on a dedicated
        // server, so push the freshly-loaded slot down to everyone online.
        APStateSync.broadcast();
    }

    @Override
    public void onReceivedItems(ArchipelagoClient client, APReceivedPacket packet) {
        // New items change what is reachable, which is most of what the tracker draws. Only the
        // item list is sent, and only once per tick however many packets arrive.
        APStateSync.markProgressDirty();
    }

    @Override
    public void onRoomUpdate(ArchipelagoClient client, APReceivedPacket packet) {
        // Checks land here, including OTHER players' - the whole point of a shared run is that
        // their progress recolours your tab too.
        APStateSync.markProgressDirty();
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
        DeathLinkService.applyRemote(data);
    }
}
