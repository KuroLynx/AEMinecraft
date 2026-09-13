package fr.euclesia.mcarchipelago.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.server.ap.HintLocationListener;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderTrackerState;
import fr.euclesia.mcarchipelago.server.gameplay.FinderTarget;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.runtime.APSlotGate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Builds and pushes the session snapshot the client tracker runs on.
 *
 * <p>Sent whenever the picture the client is drawing could have changed: on join, when the session
 * connects, and when items or checks move. Those last two are the ones that matter in multiplayer —
 * another player's check is invisible to you otherwise, and the whole point of a shared run is that
 * their progress is your progress.
 *
 * <p>Two payloads, split by how often they change. Slot data is fixed for the session and enormous —
 * a BACAP logic graph is ~2.6 MB of JSON — so it goes once, on join and on connect. Items and checks
 * move constantly and are a few kilobytes, so they go on their own ({@link APProgressPayload}),
 * coalesced to at most one send per server tick.
 *
 * <p>That split is not a micro-optimisation. The first version sent everything together on every
 * ReceivedItems packet, from the WebSocket read thread. With items trickling in it merely wasted
 * bandwidth; when a player finished their game and released, items arrived as a long stream of
 * packets and the session thread spent it re-serializing and re-compressing a graph that had not
 * changed since connect.
 *
 * <p>Still a snapshot rather than a diff: both payloads carry the full current list, so a dropped or
 * reordered update cannot leave a client permanently out of step.
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
        PayloadTypeRegistry.clientboundPlay().register(FinderSyncPayload.TYPE, FinderSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(ChatFilterSyncPayload.TYPE, ChatFilterSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(BiomeTrackerSyncPayload.TYPE, BiomeTrackerSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(APProgressPayload.TYPE, APProgressPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HintLocationsPayload.TYPE, HintLocationsPayload.CODEC);
        // Progress updates are coalesced onto the tick; see markProgressDirty.
        ServerTickEvents.END_SERVER_TICK.register(server -> flushProgress());
    }

    /**
     * Note that items or checks moved. The send happens on the next server tick.
     *
     * <p>Called straight from the Archipelago packet handlers, which run on the WebSocket read
     * thread and can fire many times in a row — a player finishing their game and releasing delivers
     * items in a long stream. Sending from there meant one full rebuild and broadcast per packet;
     * coalescing collapses a burst into a single update and moves the work onto the server thread
     * where world state is safe to read.
     */
    public static void markProgressDirty() {
        progressDirty = true;
    }

    private static volatile boolean progressDirty;

    private static void flushProgress() {
        if (!progressDirty) {
            return;
        }
        progressDirty = false;
        MinecraftServer server = AEMServerRuntime.server();
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        // APSlotGate, not the socket: an offline run still has slot data and still makes checks, so
        // its clients still need the progress that colours their tracker.
        if (server == null || !APSlotGate.isReady()) {
            return;
        }
        APProgressPayload payload = new APProgressPayload(
                List.copyOf(client.registries().apItems().receivedOrder()),
                List.copyOf(client.state().checkedLocations()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, APProgressPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    /** Pushes one player's Structure Finder bar. Cheap and per player, so it is sent on change. */
    public static void sendFinder(ServerPlayer player, int tier, List<FinderTarget> targets) {
        if (!ServerPlayNetworking.canSend(player, FinderSyncPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new FinderSyncPayload(tier, targets));
    }

    /** Pushes the chat filter's current on/off state to one player. Sent on toggle and on join. */
    public static void sendChatFilter(ServerPlayer player, boolean enabled) {
        if (!ServerPlayNetworking.canSend(player, ChatFilterSyncPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new ChatFilterSyncPayload(enabled));
    }

    /** Pushes where this slot's hinted items are to one player. Sent on join and on every hint change. */
    public static void sendHints(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, HintLocationsPayload.TYPE)) {
            ServerPlayNetworking.send(player, new HintLocationsPayload(HintLocationListener.all()));
        }
    }

    /** Pushes one player's tracked biome (the Biome Finder HUD indicator). Sent on each pick. */
    public static void sendBiomeTracker(ServerPlayer player, BiomeFinderTrackerState.Target target) {
        if (!ServerPlayNetworking.canSend(player, BiomeTrackerSyncPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, BiomeTrackerSyncPayload.of(target));
    }

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
        if (!APSlotGate.isReady() || client.state().slotData() == null) {
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
