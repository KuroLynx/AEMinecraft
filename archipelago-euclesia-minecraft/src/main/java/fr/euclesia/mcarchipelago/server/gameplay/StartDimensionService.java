package fr.euclesia.mcarchipelago.server.gameplay;

import com.google.gson.Gson;
import com.mojang.datafixers.util.Pair;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Places a freshly joined player in the dimension chosen by the Archipelago {@code start_dimension}
 * slot option. Only {@code nether} requires action — {@code overworld} keeps the vanilla spawn.
 *
 * <p>A Nether start drops the player on natural Nether terrain near a crimson/warped forest (the only
 * Nether wood source) — the same survivable footing a Nether portal would give you, not a floating
 * obsidian platform. The placement teleport does NOT grant the story "We Need to Go Deeper" advancement:
 * it is suppressed for the duration of the teleport (see {@code PlayerAdvancementsMixin}), so the player
 * earns it the normal way, by later travelling through an actual portal. The Nether tab root
 * ({@code nether/root}, which shares the title) is left alone and still fires on the start.
 *
 * <p>The decision is recorded once per player in a persistent attachment so it never re-fires: a
 * Nether-start player who has already been relocated keeps their own respawn point across deaths and
 * server restarts instead of being yanked back.
 *
 * <p>Slot data is only known once the Archipelago session is connected, so {@link #applyIfNeeded}
 * is a no-op until then. It is invoked both on player join (covers connect-before-join, e.g. the
 * main-menu connect flow) and on connect (covers connect-after-join via the {@code /archipelago
 * connect} command), whichever happens last.
 */
public final class StartDimensionService {

    /**
     * Per-world record of players already placed for a Nether start, stored in the world folder so it
     * survives relog and restart (a Fabric persistent attachment did not reliably survive on players).
     */
    private static final String STARTED_FILE = "archipelago_nether_started.json";
    private static final Gson GSON = new Gson();

    private static final String NETHER = "nether";

    /** Search reach for the nearest crimson/warped forest; matches the Biome Finder / {@code /locate}. */
    private static final int BIOME_SEARCH_RADIUS = 6400;
    private static final int BIOME_HORIZONTAL_STEP = 32;
    private static final int BIOME_VERTICAL_STEP = 64;

    /** Navigable Nether band: above the lava ocean (~y31), below the bedrock roof (~y123). */
    private static final int SCAN_TOP_Y = 122;
    private static final int SCAN_BOTTOM_Y = 33;
    /** How far out from the forest column to look for a safe footing before giving up. */
    private static final int COLUMN_SEARCH_RADIUS = 12;
    /** Y for the last-resort carved footing if no natural safe spot is found anywhere nearby. */
    private static final int FALLBACK_Y = 70;

    /** Set only across the start-placement teleport so it doesn't grant "We Need to Go Deeper". */
    private static volatile boolean suppressNetherEntryAdvancement;

    private StartDimensionService() {}

    /** Whether the "We Need to Go Deeper" advancement should be skipped right now (start placement). */
    public static boolean isSuppressingNetherEntryAdvancement() {
        return suppressNetherEntryAdvancement;
    }

    /** Applies the start dimension to every online player (used on connect). */
    public static void applyToOnlinePlayers() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }
        server.execute(() -> server.getPlayerList().getPlayers().forEach(StartDimensionService::applyIfNeeded));
    }

    /**
     * Relocates the player if the slot data is known and the start dimension has not been resolved
     * for them yet. Must run on the server thread.
     */
    public static void applyIfNeeded(ServerPlayer player) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return; // Slot data not known yet; retried on connect.
        }
        // Only a Nether start relocates the player; an Overworld start keeps the vanilla spawn and
        // needs no per-player bookkeeping (re-running it is a no-op).
        if (!NETHER.equalsIgnoreCase(AEM.ARCHIPELAGO.client().state().parsedSlotData().startDimension())) {
            return;
        }

        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }

        String uuid = player.getUUID().toString();
        Set<String> started = readStarted(server);
        if (started.contains(uuid)) {
            return; // Already placed in a previous session — never yank the player back.
        }
        // Mark up front (before the teleport) so a second call this session — e.g. JOIN then the
        // connect handler — sees it and can't double-place.
        started.add(uuid);
        writeStarted(server, started);
        placeInNether(player);
    }

    private static Set<String> readStarted(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(STARTED_FILE);
        if (!Files.exists(file)) {
            return new HashSet<>();
        }
        try {
            String[] ids = GSON.fromJson(Files.readString(file), String[].class);
            return ids == null ? new HashSet<>() : new HashSet<>(Arrays.asList(ids));
        } catch (Exception exception) {
            AEM.LOGGER.warn("Failed to read {}", STARTED_FILE, exception);
            return new HashSet<>();
        }
    }

    private static void writeStarted(MinecraftServer server, Set<String> started) {
        try {
            Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve(STARTED_FILE), GSON.toJson(started));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Failed to write {}", STARTED_FILE, exception);
        }
    }

    private static void placeInNether(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }

        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null) {
            AEM.LOGGER.warn("Nether start requested but the Nether dimension is unavailable");
            return;
        }

        // Aim for a crimson/warped forest near the scaled overworld spawn so wood (stems) is reachable;
        // fall back to the scaled spawn column if none is found within range.
        BlockPos scaledSpawn = new BlockPos(
                server.overworld().getRespawnData().pos().getX() / 8, FALLBACK_Y,
                server.overworld().getRespawnData().pos().getZ() / 8);
        BlockPos forest = nearestNetherWoodColumn(nether, scaledSpawn);

        BlockPos spot = findSafeStandingSpot(nether, forest);
        if (spot == null) {
            AEM.LOGGER.warn("No natural Nether footing found near {}; carving a minimal one", forest);
            spot = carveEmergencyFooting(nether, forest);
        }

        double x = spot.getX() + 0.5;
        double y = spot.getY();
        double z = spot.getZ() + 0.5;
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        // Don't let this one teleport award "We Need to Go Deeper" (and send its check). The award
        // happens synchronously inside teleportTo, so the flag only needs to span this call; the Nether
        // tab root (nether/root) is unaffected and still fires.
        suppressNetherEntryAdvancement = true;
        try {
            player.teleportTo(nether, x, y, z, Set.of(), yaw, pitch, true);
        } finally {
            suppressNetherEntryAdvancement = false;
        }

        // Make this the player's spawn so deaths return to the Nether, not the overworld.
        player.setRespawnPosition(
                new ServerPlayer.RespawnConfig(
                        LevelData.RespawnData.of(Level.NETHER, spot, yaw, pitch), true),
                false);

        AEM.LOGGER.info("Placed {} for a Nether start at {}", player.getGameProfile().name(), spot);
    }

    /** The column (x,z) of the nearest crimson/warped forest, or {@code center} if none is in range. */
    private static BlockPos nearestNetherWoodColumn(ServerLevel nether, BlockPos center) {
        Predicate<Holder<Biome>> isNetherWood =
                holder -> holder.is(Biomes.CRIMSON_FOREST) || holder.is(Biomes.WARPED_FOREST);
        Pair<BlockPos, Holder<Biome>> nearest = nether.findClosestBiome3d(
                isNetherWood, center, BIOME_SEARCH_RADIUS, BIOME_HORIZONTAL_STEP, BIOME_VERTICAL_STEP);
        return nearest != null ? nearest.getFirst() : center;
    }

    /**
     * Searches outward from {@code center} for a column with a solid floor and two clear blocks above,
     * so the player lands standing on natural terrain. Returns the feet position, or {@code null}.
     */
    private static BlockPos findSafeStandingSpot(ServerLevel level, BlockPos center) {
        for (int radius = 0; radius <= COLUMN_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue; // only the new ring at this radius
                    }
                    BlockPos spot = safeFooting(level, center.getX() + dx, center.getZ() + dz);
                    if (spot != null) {
                        return spot;
                    }
                }
            }
        }
        return null;
    }

    /** Top-down scan of one column for a solid floor with two clear blocks above; feet pos or null. */
    private static BlockPos safeFooting(ServerLevel level, int x, int z) {
        for (int y = SCAN_TOP_Y; y >= SCAN_BOTTOM_Y; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (isStandableFloor(level, feet.below()) && isClear(level, feet) && isClear(level, feet.above())) {
                return feet;
            }
        }
        return null;
    }

    private static boolean isStandableFloor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.blocksMotion()
                && state.getFluidState().isEmpty()
                && !state.is(Blocks.MAGMA_BLOCK);
    }

    private static boolean isClear(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.blocksMotion()
                && state.getFluidState().isEmpty()
                && !state.is(Blocks.FIRE)
                && !state.is(Blocks.SOUL_FIRE);
    }

    /** Last resort when no natural footing exists: a single netherrack block with air above it. */
    private static BlockPos carveEmergencyFooting(ServerLevel level, BlockPos center) {
        BlockPos feet = new BlockPos(center.getX(), FALLBACK_Y, center.getZ());
        level.setBlockAndUpdate(feet.below(), Blocks.NETHERRACK.defaultBlockState());
        level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
        return feet;
    }
}
