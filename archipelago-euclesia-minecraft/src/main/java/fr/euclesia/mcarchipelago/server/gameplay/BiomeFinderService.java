package fr.euclesia.mcarchipelago.server.gameplay;

import com.mojang.datafixers.util.Pair;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.content.BiomeFinderItem;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server-side brains of the {@link BiomeFinderItem}: grants the compass when the Archipelago item is
 * owned, lists the biomes searchable in a dimension (for the pick screen), and on a pick runs one
 * worldgen biome search and points the compass's vanilla lodestone needle at the nearest instance.
 *
 * <p>Like the Structure Finder, this relies on the integrated-server shared JVM: the client pick
 * screen reads {@link #availableBiomes} and submits picks through {@link #requestSearch} directly,
 * with no custom network packet.
 */
public final class BiomeFinderService {
    /** Search reach for {@code findClosestBiome3d}; matches the vanilla {@code /locate biome} command. */
    private static final int SEARCH_RADIUS = 6400;
    private static final int HORIZONTAL_STEP = 32;
    private static final int VERTICAL_STEP = 64;

    /** Finder stack (with its lodestone tracking) saved at death, restored on the next respawn. */
    private static final Map<UUID, ItemStack> savedOnDeath = new HashMap<>();

    private BiomeFinderService() {}

    /** The vanilla translation key for a biome's display name, e.g. {@code biome.minecraft.plains}. */
    public static String biomeTranslationKey(Identifier biomeId) {
        return "biome." + biomeId.getNamespace() + "." + biomeId.getPath();
    }

    /** Whether the connected slot has received the Biome Finder Archipelago item. */
    public static boolean owns(ServerPlayer player) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return false;
        }
        return AEM.ARCHIPELAGO.client().registries().apItems().receivedCount(BiomeFinderItem.AP_ITEM) > 0;
    }

    /** Gives {@code player} a Biome Finder if they own it and don't already carry one. */
    public static void ensureGranted(ServerPlayer player) {
        if (!owns(player) || BiomeFinderItem.has(player)) {
            return;
        }
        ItemStack stack = BiomeFinderItem.createStack();
        if (!player.addItem(stack)) {
            player.drop(stack, false);
        }
    }

    /**
     * Death handler: remember the player's finder (with its tracked biome) so respawn can restore the
     * same stack, then strip it from the inventory so it isn't dropped with the rest of the loot.
     */
    public static void onDeath(ServerPlayer player) {
        ItemStack finder = BiomeFinderItem.findFirst(player);
        if (!finder.isEmpty()) {
            savedOnDeath.put(player.getUUID(), finder.copy());
        }
        BiomeFinderItem.removeAll(player);
    }

    /**
     * Respawn handler: give back the exact finder saved at death (keeping the tracked biome). Falls
     * back to a fresh grant if nothing was saved (e.g. first spawn, or the finder arrived post-death).
     */
    public static void restoreOnRespawn(ServerPlayer player) {
        ItemStack saved = savedOnDeath.remove(player.getUUID());
        if (saved != null && !saved.isEmpty()) {
            player.addItem(saved);
            return;
        }
        ensureGranted(player);
    }

    /** Ensures every online player who owns the finder is carrying one (called after items arrive). */
    public static void ensureGrantedToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ensureGranted(player);
        }
    }

    /**
     * The biome ids that can generate in {@code dimension}, sorted by id. Read from the integrated
     * server's level for the pick screen; empty if there is no server or no such level.
     */
    public static List<Identifier> availableBiomes(ResourceKey<Level> dimension) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return List.of();
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return List.of();
        }
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
     * Schedules a biome search for the player with {@code playerId} on the server thread (the pick
     * screen runs on the client thread). No-op if there is no integrated server or the player is gone.
     */
    public static void requestSearch(UUID playerId, Identifier biomeId) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                search(player, biomeId);
            }
        });
    }

    /**
     * Finds the nearest {@code biomeId} in the player's current dimension and points their finder
     * compass at it (via the lodestone tracker, untracked so no lodestone block is needed). On no
     * match within range, leaves the needle alone and sends a red chat message. Server thread only.
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

        ItemStack finder = BiomeFinderItem.findFirst(player);
        if (finder.isEmpty()) {
            return;  // lost the compass between opening the screen and picking; nothing to point.
        }
        BlockPos pos = nearest.getFirst();
        finder.set(DataComponents.LODESTONE_TRACKER,
                new LodestoneTracker(Optional.of(GlobalPos.of(level.dimension(), pos)), false));
        player.sendSystemMessage(Component.translatable(
                        "message.aem.biome_finder.tracking", biomeName, pos.getX(), pos.getZ())
                .withStyle(ChatFormatting.GREEN));
    }
}
