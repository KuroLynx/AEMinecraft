package fr.euclesia.mcarchipelago.server.ap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.net.APStateSync;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;
import fr.euclesia.mcarchipelago.registry.AEMRegistries;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where this slot's hinted items are waiting, so an unlock tile can say so in its description.
 *
 * <p>The server keeps every hint in the read-only data storage key {@code _read_hints_<team>_<slot>}:
 * fetched once on connect and subscribed to, so a hint anyone asks for later arrives as a
 * {@code SetReply} carrying the whole list again. Only hints for items this slot receives and has not
 * yet been sent are kept, keyed by item id, with the finder and location already turned into names —
 * a client on a dedicated server has no data package for the other games to resolve them with.
 *
 * <p>One map for the JVM, like {@code StructureFinderState}: the server fills it here, a dedicated
 * server's clients fill their own copy from {@code HintLocationsPayload}, and in singleplayer both
 * are the same map.
 */
public final class HintLocationListener implements APEventListener {
    /** One hinted copy of an item: the slot whose world holds it, and the location there. */
    public record Spot(String player, String location) {}

    private static volatile Map<Long, List<Spot>> spots = Map.of();

    /** The unfound hinted copies of {@code itemId}, oldest hint first; empty when it has not been hinted. */
    public static List<Spot> spots(long itemId) {
        return spots.getOrDefault(itemId, List.of());
    }

    public static Map<Long, List<Spot>> all() {
        return spots;
    }

    /** Replaces the map wholesale — every source hands over the full list, never a diff. */
    public static void set(Map<Long, List<Spot>> value) {
        spots = Map.copyOf(value);
    }

    @Override
    public void onConnected(ArchipelagoClient client, APReceivedPacket packet) {
        set(Map.of()); // a different slot's hints must not survive into this one
        List<String> key = List.of(hintsKey(client));
        AEM.ARCHIPELAGO.gateway().getDataStorage(key);
        AEM.ARCHIPELAGO.gateway().subscribeDataStorage(key);
    }

    @Override
    public void onRetrieved(ArchipelagoClient client, APReceivedPacket packet) {
        JsonElement keys = packet.payload().get("keys");
        if (keys != null && keys.isJsonObject()) {
            apply(client, keys.getAsJsonObject().get(hintsKey(client)));
        }
    }

    @Override
    public void onSetReply(ArchipelagoClient client, APReceivedPacket packet) {
        if (hintsKey(client).equals(APJson.getString(packet.payload(), "key", ""))) {
            apply(client, packet.payload().get("value"));
        }
    }

    private static String hintsKey(ArchipelagoClient client) {
        return "_read_hints_" + client.state().team() + "_" + client.state().slot();
    }

    private static void apply(ArchipelagoClient client, JsonElement hints) {
        if (hints == null || !hints.isJsonArray()) {
            return;
        }
        int mySlot = client.state().slot();
        AEMRegistries registries = client.registries();
        Map<Long, List<Spot>> byItem = new HashMap<>();
        for (JsonElement element : hints.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject hint = element.getAsJsonObject();
            if (APJson.getInt(hint, "receiving_player", -1) != mySlot || APJson.getBoolean(hint, "found", false)) {
                continue;
            }
            int finder = APJson.getInt(hint, "finding_player", -1);
            long locationId = APJson.getLong(hint, "location", -1L);
            String player = client.state().playerName(finder);
            String location = registries.apGameData().locationName(finder, locationId)
                    .orElseGet(() -> registries.apLocations().nameForId(locationId).orElse(String.valueOf(locationId)));
            byItem.computeIfAbsent(APJson.getLong(hint, "item", -1L), id -> new ArrayList<>())
                    .add(new Spot(player != null ? player : String.valueOf(finder), location));
        }
        // Keep each item's copies in the order they were first hinted, so a progressive item's Nth tile
        // keeps pointing at the same copy. AP stores hints as a set, so the list it sends has no stable
        // order of its own: copies already known keep their place, new ones go after them.
        byItem.replaceAll((itemId, fresh) -> {
            List<Spot> ordered = new ArrayList<>(spots(itemId));
            ordered.retainAll(fresh);
            fresh.stream().filter(spot -> !ordered.contains(spot)).forEach(ordered::add);
            return ordered;
        });
        set(byItem);
        AEMDebug.log("hints: {} unfound hinted items for this slot", byItem.size());

        MinecraftServer server = AEMServerRuntime.server();
        if (server != null) {
            server.execute(() -> server.getPlayerList().getPlayers().forEach(APStateSync::sendHints));
        }
    }
}
