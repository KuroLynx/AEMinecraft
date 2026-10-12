package fr.euclesia.mcarchipelago.client.net;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.net.BiomeListPayload;
import fr.euclesia.mcarchipelago.net.BiomeListRequestPayload;
import fr.euclesia.mcarchipelago.net.BiomeSearchPayload;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The client half of the Biome Finder: asks the server what it can search for, holds the answer for
 * the pick screen, and sends the pick back.
 *
 * <p>The list is requested fresh every time the screen opens rather than cached per dimension — it is
 * a few dozen strings, and a stale list would be a list of biomes you cannot reach from where you now
 * are.
 */
public final class BiomeFinderClient {
    private BiomeFinderClient() {}

    /** Biome ids for the dimension the last request was made from; null until an answer arrives. */
    private static volatile List<Identifier> biomes;
    /** The map that came with them; null with the list, or when the server sent none. */
    private static volatile BiomeMap map;

    /**
     * A biome map from the server: {@code size}² cells {@code step} blocks apart around
     * ({@code centerX}, {@code centerZ}), row-major from the north-west; each cell indexes
     * {@link #biomes()} (unsigned byte), 255 for none.
     */
    public record BiomeMap(int centerX, int centerZ, int step, int size, byte[] cells) {
        /** The biome index at a cell, or -1. */
        public int at(int col, int row) {
            int value = cells[row * size + col] & 0xFF;
            return value == 255 ? -1 : value;
        }
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BiomeListPayload.TYPE,
                (payload, context) -> context.client().execute(() -> accept(payload)));

        // Don't carry one world's biome list into the next.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            biomes = null;
            map = null;
        });
    }

    private static void accept(BiomeListPayload payload) {
        List<Identifier> parsed = new ArrayList<>(payload.biomeIds().size());
        for (String biomeId : payload.biomeIds()) {
            Identifier id = Identifier.tryParse(biomeId);
            if (id != null) {
                parsed.add(id);
            }
        }
        // Parsed in order, and an unparseable id is dropped — which would shift every index after it.
        // Ids come from the server's own registry, so that never happens; if it ever did, drop the map.
        map = payload.size() > 0 && parsed.size() == payload.biomeIds().size()
                ? new BiomeMap(payload.centerX(), payload.centerZ(), payload.step(), payload.size(), payload.cells())
                : null;
        biomes = List.copyOf(parsed);
    }

    /** The map that arrived with the last list, or null. */
    public static BiomeMap map() {
        return map;
    }

    /**
     * Asks for the biomes searchable where the player is standing, dropping whatever we last held so
     * the screen shows its loading state until the answer lands.
     *
     * <p>A server without this mod cannot answer, so the request is not sent and the list resolves
     * immediately to empty — a screen that says "nothing here" rather than one that waits forever.
     */
    public static void requestList() {
        if (!ClientPlayNetworking.canSend(BiomeListRequestPayload.TYPE)) {
            biomes = List.of();
            return;
        }
        biomes = null;
        map = null;
        ClientPlayNetworking.send(BiomeListRequestPayload.INSTANCE);
    }

    /** The last list received, or null while one is still in flight. */
    public static List<Identifier> biomes() {
        return biomes;
    }

    /**
     * Whether this client's session has received the Biome Finder Archipelago item — mirrors
     * {@link BiomeFinderService#owns()}'s server-side truth. Safe to read even when there is no
     * session: the item registry resets on disconnect, so this reads {@code false} rather than a
     * stale value from a previous world.
     */
    public static boolean owns() {
        return AEM.ARCHIPELAGO.client().registries().apItems().receivedCount(BiomeFinderService.AP_ITEM) > 0;
    }

    /** Stops tracking: an empty pick, which the server answers by clearing the HUD target. */
    public static void clear() {
        if (ClientPlayNetworking.canSend(BiomeSearchPayload.TYPE)) {
            ClientPlayNetworking.send(BiomeSearchPayload.clear());
        }
    }

    /** Sends a pick; the server runs the search and points the HUD tracker bar. */
    public static void pick(Identifier biomeId) {
        if (ClientPlayNetworking.canSend(BiomeSearchPayload.TYPE)) {
            ClientPlayNetworking.send(BiomeSearchPayload.nearest(biomeId.toString()));
        }
    }

    /** Sends a map click: point the HUD tracker bar at exactly this spot. */
    public static void pickSpot(int x, int z) {
        if (ClientPlayNetworking.canSend(BiomeSearchPayload.TYPE)) {
            ClientPlayNetworking.send(BiomeSearchPayload.spot(x, z));
        }
    }
}
