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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
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

    private BiomeFinderService() {}

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
     * Finds the nearest {@code biomeId} in the player's current dimension and points the HUD
     * tracker bar at it. On no match within range, leaves the tracker alone and sends a red chat
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

        BlockPos pos = nearest.getFirst();
        BiomeFinderTrackerState.Target target =
                new BiomeFinderTrackerState.Target(biomeId.toString(), level.dimension(), pos);
        BiomeFinderTrackerState.get().putTarget(player.getUUID(), target);
        // Singleplayer reads the state above straight out of this holder; a remote client has no
        // such holder to read, so the same target goes down the wire (see FinderSyncPayload for
        // why the Structure Finder bar needs the same split).
        APStateSync.sendBiomeTracker(player, target);

        player.sendSystemMessage(Component.translatable(
                        "message.aem.biome_finder.tracking", biomeName, pos.getX(), pos.getZ())
                .withStyle(ChatFormatting.GREEN));
    }
}
