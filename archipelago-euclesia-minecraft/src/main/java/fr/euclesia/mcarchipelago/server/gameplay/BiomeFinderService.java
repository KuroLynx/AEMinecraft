package fr.euclesia.mcarchipelago.server.gameplay;

import com.mojang.datafixers.util.Pair;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.net.APStateSync;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Server-side brains of the Biome Finder: lists the biomes searchable in a dimension (for the pick
 * screen) and, on a pick, runs one worldgen biome search and points the player at the nearest
 * instance. Access is gated purely on Archipelago item ownership ({@link #owns}) — there is no
 * physical item to grant, save at death, or restore on respawn.
 *
 * <p>Everything here is server-side. The pick screen talks to it over the wire
 * ({@link fr.euclesia.mcarchipelago.net.BiomeFinderNet}) — it used to call in directly, which quietly
 * did nothing on a dedicated server, where the client's JVM holds no world at all.
 */
public final class BiomeFinderService {
    /** Archipelago item whose receipt grants access to the finder. */
    public static final String AP_ITEM = "Biome Finder";

    /** Search reach for {@code findClosestBiome3d}; matches the vanilla {@code /locate biome} command. */
    private static final int SEARCH_RADIUS = 6400;
    private static final int HORIZONTAL_STEP = 32;
    private static final int VERTICAL_STEP = 64;
    /** How often (ticks) to check whether a tracking player has reached their biome. */
    private static final int ARRIVAL_CHECK_INTERVAL = 20;
    /** The pick screen's map: MAP_SIZE² cells, MAP_STEP blocks apart, so about ±2000 blocks around you. */
    private static final int MAP_SIZE = 128;
    private static final int MAP_STEP = 32;

    private static int tickCounter;
    /**
     * Players who have been OUTSIDE their target biome since it was set. Arrival means entering it, so
     * a target clears only once armed — otherwise picking a far patch of the biome you stand in (easy
     * on the map) would clear itself on the next check. Server thread only.
     */
    private static final Set<UUID> armed = new HashSet<>();

    /** A sampled biome map; see {@link fr.euclesia.mcarchipelago.net.BiomeListPayload} for the layout. */
    public record BiomeMap(int centerX, int centerZ, int step, int size, byte[] cells) {}

    private BiomeFinderService() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BiomeFinderService::checkArrivals);
    }

    /**
     * Stops tracking for every player now standing in the biome they asked for: the arrows have done
     * their job, and keeping them would point at one spot inside a biome you are already in.
     */
    private static void checkArrivals(MinecraftServer server) {
        if (++tickCounter % ARRIVAL_CHECK_INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BiomeFinderTrackerState.Target target = BiomeFinderTrackerState.get().target(player.getUUID());
            if (target == null || !target.dimension().equals(player.level().dimension())) {
                continue;
            }
            Identifier here = player.level().getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> key.identifier()).orElse(null);
            if (here == null || !here.toString().equals(target.biomeId())) {
                armed.add(player.getUUID());
            } else if (armed.contains(player.getUUID())) {
                clear(player);
                player.sendSystemMessage(Component.translatable("message.aem.biome_finder.arrived",
                        Component.translatable(biomeTranslationKey(here))).withStyle(ChatFormatting.GREEN));
            }
        }
    }

    /** Stops tracking for {@code player}, on the server and on their HUD. */
    public static void clear(ServerPlayer player) {
        UUID id = player.getUUID();
        if (BiomeFinderTrackerState.get().target(id) == null) {
            return;
        }
        BiomeFinderTrackerState.get().remove(id);
        armed.remove(id);
        APStateSync.sendBiomeTracker(player, null);
    }

    /**
     * Samples the biome layout around {@code player} at their height, straight from the biome source:
     * no chunk is loaded or generated. Each cell is the index of its biome in {@code palette} as an unsigned byte, 255 if absent.
     * ponytail: 16k noise-biome lookups on the server thread per screen opening (tens of ms); move it off
     * thread or cache per region if a big server feels it.
     */
    public static BiomeMap sampleMap(ServerPlayer player, List<Identifier> palette) {
        ServerLevel level = player.level();
        Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Map<Identifier, Integer> index = new HashMap<>();
        for (int i = 0; i < palette.size() && i < 255; i++) {
            index.put(palette.get(i), i);
        }
        int centerX = player.getBlockX();
        int centerZ = player.getBlockZ();
        int quartY = player.getBlockY() >> 2;
        byte[] cells = new byte[MAP_SIZE * MAP_SIZE];
        for (int row = 0; row < MAP_SIZE; row++) {
            int z = centerZ + (row - MAP_SIZE / 2) * MAP_STEP;
            for (int col = 0; col < MAP_SIZE; col++) {
                int x = centerX + (col - MAP_SIZE / 2) * MAP_STEP;
                Identifier id = registry.getKey(level.getUncachedNoiseBiome(x >> 2, quartY, z >> 2).value());
                cells[row * MAP_SIZE + col] = (byte) (int) index.getOrDefault(id, 255);
            }
        }
        return new BiomeMap(centerX, centerZ, MAP_STEP, MAP_SIZE, cells);
    }

    /**
     * Points {@code player} at exactly ({@code x}, {@code z}), at their own height — a click on the map.
     * The biome is read here, not taken from the client, and the spot must lie on the map they were sent.
     */
    public static void trackSpot(ServerPlayer player, int x, int z) {
        int reach = (MAP_SIZE / 2 + 1) * MAP_STEP;
        if (Math.abs(x - player.getBlockX()) > reach || Math.abs(z - player.getBlockZ()) > reach) {
            return;
        }
        ServerLevel level = player.level();
        BlockPos pos = new BlockPos(x, player.getBlockY(), z);
        Identifier biomeId = level.registryAccess().lookupOrThrow(Registries.BIOME)
                .getKey(level.getUncachedNoiseBiome(x >> 2, pos.getY() >> 2, z >> 2).value());
        if (biomeId != null) {
            track(player, biomeId, pos);
        }
    }

    /** Sets {@code player}'s target, on the server and on their HUD, and says so in chat. */
    private static void track(ServerPlayer player, Identifier biomeId, BlockPos pos) {
        BiomeFinderTrackerState.Target target =
                new BiomeFinderTrackerState.Target(biomeId.toString(), player.level().dimension(), pos);
        BiomeFinderTrackerState.get().putTarget(player.getUUID(), target);
        armed.remove(player.getUUID());
        // Singleplayer reads the state above straight out of this holder; a remote client has no
        // such holder to read, so the same target goes down the wire (see FinderSyncPayload for
        // why the Structure Finder bar needs the same split).
        APStateSync.sendBiomeTracker(player, target);
        player.sendSystemMessage(Component.translatable("message.aem.biome_finder.tracking",
                        Component.translatable(biomeTranslationKey(biomeId)), pos.getX(), pos.getZ())
                .withStyle(ChatFormatting.GREEN));
    }

    /** The vanilla translation key for a biome's display name, e.g. {@code biome.minecraft.plains}. */
    public static String biomeTranslationKey(Identifier biomeId) {
        return "biome." + biomeId.getNamespace() + "." + biomeId.getPath();
    }

    /**
     * Whether the connected slot has received the Biome Finder Archipelago item. Deliberately takes
     * no player: a server is many people playing one Archipelago slot, so this is a run-wide
     * capability, not a personal one — same reasoning as {@link StructureFinderService#tier()}.
     */
    public static boolean owns() {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return false;
        }
        return AEM.ARCHIPELAGO.client().registries().apItems().receivedCount(AP_ITEM) > 0;
    }

    /**
     * The biome ids {@code level} can generate, sorted by id — what the pick screen offers. Server
     * thread only: it reads the level's chunk generator, which is why the client has to ask for it
     * (see {@link fr.euclesia.mcarchipelago.net.BiomeFinderNet}) rather than work it out itself.
     */
    public static List<Identifier> availableBiomes(ServerLevel level) {
        Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        List<Identifier> ids = new ArrayList<>();
        for (Holder<Biome> holder : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
            Identifier id = registry.getKey(holder.value());
            if (id != null) {
                ids.add(id);
            }
        }
        ids.sort(Comparator.comparing(Identifier::toString));
        return ids;
    }

    /**
     * Finds the nearest {@code biomeId} in the player's current dimension and points the ground
     * arrows at it. On no match within range, leaves the tracker alone and sends a red chat
     * message. Server thread only.
     */
    public static void search(ServerPlayer player, Identifier biomeId) {
        ServerLevel level = player.level();
        Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Component biomeName = Component.translatable(biomeTranslationKey(biomeId));

        Predicate<Holder<Biome>> matches = holder -> biomeId.equals(registry.getKey(holder.value()));
        Pair<BlockPos, Holder<Biome>> nearest = level.findClosestBiome3d(
                matches, player.blockPosition(), SEARCH_RADIUS, HORIZONTAL_STEP, VERTICAL_STEP);

        if (nearest == null) {
            player.sendSystemMessage(Component.translatable("message.aem.biome_finder.none", biomeName)
                    .withStyle(ChatFormatting.RED));
            return;
        }

        track(player, biomeId, nearest.getFirst());
    }
}
