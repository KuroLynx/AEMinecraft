package fr.euclesia.mcarchipelago.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.archipelago.ChatFilterPreference;
import fr.euclesia.mcarchipelago.client.hint.HintHoldTracker;
import fr.euclesia.mcarchipelago.net.APProgressPayload;
import fr.euclesia.mcarchipelago.net.APStateSyncPayload;
import fr.euclesia.mcarchipelago.net.BiomeTrackerSyncPayload;
import fr.euclesia.mcarchipelago.net.ChatFilterSyncPayload;
import fr.euclesia.mcarchipelago.net.FinderSyncPayload;
import fr.euclesia.mcarchipelago.net.HintLocationsPayload;
import fr.euclesia.mcarchipelago.server.ap.HintLocationListener;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderTrackerState;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderState;
import fr.euclesia.mcarchipelago.protocol.APItemClassification;
import fr.euclesia.mcarchipelago.protocol.packet.inbound.APNetworkItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies the server's session snapshot into the client's own Archipelago instance.
 *
 * <p>The trick here is that the client already has a fully-formed {@code AEM.ARCHIPELAGO} — it is
 * simply never connected when playing on a dedicated server. So rather than teach every piece of UI
 * a second way to find its data, the snapshot is poured into that instance: same slot data, same
 * registries, same "connected" flag. The advancement overlay, the tracker tab and the goal tiles
 * then work on a server exactly as they do in singleplayer, with no changes to any of them.
 *
 * <p>Nothing here ever sends: this instance has no transport. It is a local mirror of the server's
 * session, and the server remains the only thing talking to Archipelago.
 *
 * <p>Cleared on disconnect, so leaving a server does not leave a stale seed's colours behind on the
 * next world you open.
 */
public final class APStateSyncClient {
    private APStateSyncClient() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(APStateSyncPayload.TYPE,
                (payload, context) -> {
                    String json = payload.json();
                    if (json != null) {
                        context.client().execute(() -> apply(json));
                    }
                });

        // Progress: items and checks only, arriving far more often than slot data changes (never).
        // Applied on top of whatever slot data we already hold; if none has arrived yet there is
        // nothing to evaluate against, so it is ignored and the next full sync will carry it.
        ClientPlayNetworking.registerGlobalReceiver(APProgressPayload.TYPE,
                (payload, context) -> context.client().execute(() -> applyProgress(payload)));

        // The Structure Finder bar. The HUD reads the same server-side holder the driver publishes
        // into, which is empty in this JVM on a dedicated server — so the snapshot is poured into
        // the local copy under our own uuid and the HUD finds it exactly where it expects.
        ClientPlayNetworking.registerGlobalReceiver(FinderSyncPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    LocalPlayer player = context.client().player;
                    if (player == null) {
                        return;
                    }
                    if (payload.tier() <= 0 || payload.targets().isEmpty()) {
                        StructureFinderState.get().remove(player.getUUID());
                        return;
                    }
                    StructureFinderState.get().putSnapshot(player.getUUID(),
                            new StructureFinderState.Snapshot(payload.tier(), payload.targets()));
                }));

        // The chat filter's on/off state. Only the first sync after (re)connecting is silent — a
        // freshly joined client should not announce whatever the world already had persisted;
        // every sync after that reflects an actual toggle, so it is worth a confirmation line.
        ClientPlayNetworking.registerGlobalReceiver(ChatFilterSyncPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    ChatFilterPreference.setEnabled(payload.enabled());
                    Boolean previous = lastKnownChatFilter;
                    lastKnownChatFilter = payload.enabled();
                    if (previous != null && previous != payload.enabled()) {
                        LocalPlayer player = context.client().player;
                        if (player != null) {
                            player.sendSystemMessage(Component.translatable(
                                    payload.enabled() ? "message.aem.chatfilter.on" : "message.aem.chatfilter.off"));
                        }
                    }
                }));

        // The Biome Finder HUD tracker. Same reasoning as the Structure Finder bar above: this
        // JVM's own BiomeFinderTrackerState is empty on a dedicated server, so the pushed target
        // is poured into it under our own uuid.
        ClientPlayNetworking.registerGlobalReceiver(BiomeTrackerSyncPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    LocalPlayer player = context.client().player;
                    if (player == null) {
                        return;
                    }
                    BiomeFinderTrackerState.Target target = payload.toTarget();
                    if (target == null) {
                        BiomeFinderTrackerState.get().remove(player.getUUID());
                    } else {
                        BiomeFinderTrackerState.get().putTarget(player.getUUID(), target);
                    }
                }));

        // Where this slot's hinted items are, for the unlock tiles' descriptions. In singleplayer the
        // server already filled this same map, so setting it again changes nothing.
        ClientPlayNetworking.registerGlobalReceiver(HintLocationsPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    HintLocationListener.set(payload.spots());
                    HintHoldTracker.reopenAfterHint();
                }));

        // Leaving a server (or an integrated world) drops the mirror. In singleplayer this instance
        // is the REAL session, so only clear what we ourselves populated.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (mirrored) {
                clear();
            }
        });
    }

    /** Whether the local session is a mirror of a server's rather than our own connection. */
    private static volatile boolean mirrored;

    /** {@code null} until the first chat-filter sync arrives; see the receiver above. */
    private static volatile Boolean lastKnownChatFilter;

    public static boolean isMirrored() {
        return mirrored;
    }

    private static void apply(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            return;
        }
        JsonObject root = parsed.getAsJsonObject();
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();

        // Singleplayer already has the real thing; never overwrite a live session with a mirror.
        if (client.state().isConnected() && !mirrored) {
            return;
        }

        JsonObject slotData = root.has("slot_data") && root.get("slot_data").isJsonObject()
                ? root.getAsJsonObject("slot_data")
                : new JsonObject();
        client.state().setSlotData(slotData);
        client.registries().apItems().resetReceived();
        client.registries().apItems().loadSlotData(client.state().parsedSlotData());
        client.registries().apLocations().loadSlotData(client.state().parsedSlotData());
        client.registries().apMobs().loadSlotData(client.state().parsedSlotData());
        client.registries().apStructures().loadSlotData(client.state().parsedSlotData());
        client.registries().apMaterials().loadSlotData(client.state().parsedSlotData());
        client.registries().apTrackers().loadFromSlotData(client.state().slotData());

        // Replay the received items in order. Only the item id carries meaning for the tracker —
        // which location it came from and who sent it are the server's business — so the rest of
        // the record is filled with the same "unknown" values an unscouted item would carry.
        int items = 0;
        for (JsonElement element : array(root, "received")) {
            client.registries().apItems().markReceived(
                    new APNetworkItem(element.getAsLong(), -1, -1, APItemClassification.NORMAL));
            items++;
        }

        List<Long> checked = new ArrayList<>();
        for (JsonElement element : array(root, "checked")) {
            checked.add(element.getAsLong());
        }
        client.registries().apLocations().markChecked(checked);
        client.state().checkedLocations().addAll(checked);

        mirrored = true;
        client.state().setConnected(true);
        AEMDebug.log("apStateSync applied: {} items, {} checks", items, checked.size());
    }

    private static void applyProgress(APProgressPayload payload) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        if (!mirrored) {
            return; // singleplayer, or slot data has not arrived yet
        }
        client.registries().apItems().resetReceived();
        for (long itemId : payload.received()) {
            client.registries().apItems().markReceived(
                    new APNetworkItem(itemId, -1, -1, APItemClassification.NORMAL));
        }
        client.state().checkedLocations().clear();
        client.state().checkedLocations().addAll(payload.checked());
        client.registries().apLocations().markChecked(payload.checked());
        AEMDebug.log("apProgress applied: {} items, {} checks",
                payload.received().size(), payload.checked().size());
    }

    private static JsonArray array(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : new JsonArray();
    }

    private static void clear() {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        client.state().setConnected(false);
        client.state().setSlotData(new JsonObject());
        client.state().checkedLocations().clear();
        client.registries().apItems().resetReceived();
        // The finder bar too, or it hangs around pointing at the last server's structures.
        StructureFinderState.get().clear();
        lastKnownChatFilter = null;
        BiomeFinderTrackerState.get().clear();
        HintLocationListener.set(Map.of());
        mirrored = false;
        AEMDebug.log("apStateSync cleared (left the server)");
    }
}
