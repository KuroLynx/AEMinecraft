package fr.euclesia.mcarchipelago.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.net.APStateSyncPayload;
import fr.euclesia.mcarchipelago.net.FinderSyncPayload;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderState;
import fr.euclesia.mcarchipelago.protocol.APItemClassification;
import fr.euclesia.mcarchipelago.protocol.packet.inbound.APNetworkItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;

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
        mirrored = false;
        AEMDebug.log("apStateSync cleared (left the server)");
    }
}
