package fr.euclesia.mcarchipelago.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builds and pushes the session snapshot the client tracker runs on.
 *
 * <p>Sent whenever the picture the client is drawing could have changed: on join, when the session
 * connects, and when items or checks move. Those last two are the ones that matter in multiplayer —
 * another player's check is invisible to you otherwise, and the whole point of a shared run is that
 * their progress is your progress.
 *
 * <p>A snapshot rather than a diff, on purpose. The state is small once compressed, the events are
 * infrequent, and a diff stream has to be perfectly ordered and perfectly complete or the client
 * drifts out of sync with no way to notice. Re-sending the truth costs a few kilobytes and cannot
 * drift.
 */
public final class APStateSync {
    private static final Gson GSON = new Gson();

    private APStateSync() {}

    /**
     * Registers the payload type. Must run on both sides, at init, before any send.
     *
     * <p>{@code registerLarge} rather than {@code register}: the default ceiling is the vanilla
     * payload limit, and a slot's compressed logic graph can sit near it. The bound is still a
     * bound — it just leaves room for the one payload this mod sends that is genuinely big.
     */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay()
                .registerLarge(APStateSyncPayload.TYPE, APStateSyncPayload.CODEC, MAX_PAYLOAD_BYTES);
    }

    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    /** Pushes the current session to one player. */
    public static void sendTo(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, APStateSyncPayload.TYPE)) {
            return; // a vanilla client, or one without this mod: nothing to draw the tracker with
        }
        String json = snapshot();
        if (json == null) {
            return;
        }
        ServerPlayNetworking.send(player, APStateSyncPayload.of(json));
    }

    /** Pushes the current session to everyone online. */
    public static void broadcast() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        String json = snapshot();
        if (json == null) {
            return;
        }
        // Compress once for the whole player list rather than per recipient.
        APStateSyncPayload payload = APStateSyncPayload.of(json);
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Skip clients that cannot receive it. Sending an unknown payload to a vanilla
                // client is not harmless — it is a protocol error on their end.
                if (ServerPlayNetworking.canSend(player, APStateSyncPayload.TYPE)) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
        });
    }

    /**
     * The session as the client needs to see it: the slot data it evaluates, the items it has, and
     * the checks already sent. Null when there is no session to describe, so a client is never fed
     * an empty snapshot that would read as "connected with nothing".
     */
    private static String snapshot() {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        if (!client.state().isConnected() || client.state().slotData() == null) {
            return null;
        }
        JsonObject root = new JsonObject();
        root.add("slot_data", client.state().slotData());

        // Received items in arrival order, duplicates included: the client replays them, and the
        // reachability pass cares how MANY of a progressive item you hold, not just whether you do.
        JsonArray received = new JsonArray();
        client.registries().apItems().receivedOrder().forEach(received::add);
        root.add("received", received);

        JsonArray checked = new JsonArray();
        client.state().checkedLocations().forEach(checked::add);
        root.add("checked", checked);

        String json = GSON.toJson(root);
        AEMDebug.log("apStateSync snapshot {} received, {} checked, {} chars",
                received.size(), checked.size(), json.length());
        return json;
    }
}
