package fr.euclesia.mcarchipelago.server.gameplay;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Biome Finder tracker state: the biome last located for each player, written directly by
 * {@link BiomeFinderService#search} and read by the client HUD.
 *
 * <p>Same shared-singleton pattern as {@link StructureFinderState} and for the same reason: the
 * mod runs an integrated server on singleplayer, so client and server share this JVM and this
 * singleton for free there — but on a dedicated server they are different JVMs, so the value also
 * has to travel over the wire ({@code fr.euclesia.mcarchipelago.net.BiomeTrackerSyncPayload} via
 * {@code APStateSync#sendBiomeTracker}) into the client's own copy of this same singleton.
 */
public final class BiomeFinderTrackerState {
    private static final BiomeFinderTrackerState INSTANCE = new BiomeFinderTrackerState();

    /** The biome last pointed at: its id, the dimension it was found in, and its position. */
    public record Target(String biomeId, ResourceKey<Level> dimension, BlockPos pos) {}

    private final Map<UUID, Target> targets = new ConcurrentHashMap<>();

    private BiomeFinderTrackerState() {}

    public static BiomeFinderTrackerState get() {
        return INSTANCE;
    }

    public void putTarget(UUID player, Target target) {
        targets.put(player, target);
    }

    /** The last tracked biome for {@code player}, or {@code null} if none. */
    public Target target(UUID player) {
        return targets.get(player);
    }

    public void remove(UUID player) {
        targets.remove(player);
    }

    /** Drops every tracked target. Used client-side on leaving a server. */
    public void clear() {
        targets.clear();
    }
}
