package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderState.Snapshot;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the Structure-Finder search and publishes results to {@link StructureFinderState} for the
 * client to render.
 *
 * <p>The search is heavy — one {@code findNearestMapStructure} ({@code /locate}-style worldgen scan)
 * per eligible structure, and a single rare structure can scan the whole radius on its own — so it is
 * kept off the moment a finder is received and off the moment a player enters the world:
 *
 * <ul>
 *   <li><b>One scan per dimension, anchored at the spawn.</b> The result is shared by every player in
 *       that dimension; the finder tier only decides how many of the structures each player sees. The
 *       scan is independent of the finder, so receiving one never triggers it.</li>
 *   <li><b>The starting dimension is scanned at server start</b>, all at once, while the world-loading
 *       screen is still up (the integrated server only accepts the client once startup finishes). The
 *       player therefore spawns into a world where the finder is already located — no in-world stutter.
 *       The start dimension follows the {@code start_dimension} slot option (overworld → world spawn;
 *       nether → the scaled spawn the player is dropped near). By this point the Archipelago pre-flight
 *       connect has completed (it gates {@code doWorldLoad}), so the slot data needed to scan is
 *       available.</li>
 *   <li><b>Other dimensions are scanned lazily</b> the first time a player is in them, anchored at that
 *       player's position and spread across ticks under a per-tick budget ({@link #TICK_BUDGET_NANOS});
 *       those transitions have their own loading screens. A structure unlock (which changes what is
 *       findable) invalidates the affected dimension's cache, which is rescanned the same way.</li>
 * </ul>
 *
 * <p>The cached set is anchored to the spawn and is not refreshed as players travel, but the HUD
 * derives each structure's bearing, distance and icon size from the live player position, so the bar
 * stays accurate while walking.
 */
public final class StructureFinderDriver {
    /** Upper bound on time spent advancing lazy (non-starting-dimension) scans each tick (~1.5 ms). */
    private static final long TICK_BUDGET_NANOS = 1_500_000L;
    /** Slot value of {@code start_dimension} for a Nether start. */
    private static final String NETHER_START = "nether";
    /** Y used to anchor the Nether scan; structure location is effectively horizontal, so it is nominal. */
    private static final int NETHER_ANCHOR_Y = 64;

    /** Completed scan per dimension, the source each player's display is capped from. */
    private static final Map<ResourceKey<Level>, Cache> CACHES = new HashMap<>();
    /** In-flight lazy scans, one per dimension. Server-thread only. */
    private static final Map<ResourceKey<Level>, ScanJob> JOBS = new HashMap<>();
    /** What was last pushed to each player's display, so an unchanged tier+cache isn't re-published. */
    private static final Map<UUID, Published> PUBLISHED = new HashMap<>();

    private StructureFinderDriver() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(StructureFinderDriver::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(StructureFinderDriver::onEndTick);
    }

    /**
     * Pre-warm the starting dimension's scan while the world is still loading. Runs all at once (no
     * per-tick budget) on purpose: the integrated server has not yet accepted the client, so this time
     * is spent on the loading screen rather than as an in-world freeze.
     */
    private static void onServerStarted(MinecraftServer server) {
        CACHES.clear();
        JOBS.clear();
        PUBLISHED.clear();
        if (!AEMServerRuntime.isArchipelagoReady() || !finderActive()) {
            return;
        }
        ScanAnchor anchor = startAnchor(server);
        if (anchor == null) {
            return;
        }
        int structureVersion = structureVersion();
        List<FinderTarget> found = new ArrayList<>();
        for (Holder.Reference<Structure> ref : StructureFinderService.candidates(anchor.level())) {
            FinderTarget target = StructureFinderService.nearest(anchor.level(), anchor.origin(), ref);
            if (target != null) {
                found.add(target);
            }
        }
        found.sort(Comparator.comparingDouble(FinderTarget::distanceSq));
        CACHES.put(anchor.level().dimension(), new Cache(structureVersion, found));
    }

    /** The dimension and origin to scan at start, from the {@code start_dimension} slot option. */
    private static ScanAnchor startAnchor(MinecraftServer server) {
        String startDimension = AEM.ARCHIPELAGO.client().state().parsedSlotData().startDimension();
        if (NETHER_START.equalsIgnoreCase(startDimension)) {
            ServerLevel nether = server.getLevel(Level.NETHER);
            ServerLevel overworld = server.overworld();
            if (nether != null && overworld != null) {
                // The player is dropped near the overworld spawn scaled into Nether coordinates.
                BlockPos spawn = overworld.getRespawnData().pos();
                return new ScanAnchor(nether, new BlockPos(spawn.getX() / 8, NETHER_ANCHOR_Y, spawn.getZ() / 8));
            }
        }
        ServerLevel overworld = server.overworld();
        return overworld == null ? null : new ScanAnchor(overworld, overworld.getRespawnData().pos());
    }

    private static void onEndTick(MinecraftServer server) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            CACHES.clear();
            JOBS.clear();
            PUBLISHED.clear();
            return;
        }
        StructureFinderState state = StructureFinderState.get();
        int structureVersion = structureVersion();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        if (finderActive()) {
            for (ServerPlayer player : players) {
                ensureScan(player, structureVersion);
            }
            advanceJobs(structureVersion);
        }
        for (ServerPlayer player : players) {
            publish(state, player);
        }
    }

    /**
     * Starts (or restarts on a structure unlock) the scan for the player's current dimension if it is
     * not already cached, anchoring it at the player's position. The starting dimension is normally
     * already cached by {@link #onServerStarted}, so this only fires for other dimensions (or as a
     * fallback if the pre-warm could not run).
     */
    private static void ensureScan(ServerPlayer player, int structureVersion) {
        ServerLevel level = player.level();
        ResourceKey<Level> dimension = level.dimension();
        ScanJob job = JOBS.get(dimension);
        if (job != null) {
            if (job.structureVersion == structureVersion) {
                return;  // already scanning this dimension at the current version
            }
            JOBS.remove(dimension);  // a structure unlocked mid-scan; restart below
        }
        Cache cache = CACHES.get(dimension);
        if (cache != null && cache.structureVersion() == structureVersion) {
            return;  // fresh result already cached
        }
        JOBS.put(dimension, new ScanJob(structureVersion, level, player.blockPosition(),
                StructureFinderService.candidates(level).iterator()));
    }

    /** Advances in-flight lazy scans, sharing one time budget across them; caches each as it finishes. */
    private static void advanceJobs(int structureVersion) {
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        Iterator<Map.Entry<ResourceKey<Level>, ScanJob>> it = JOBS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceKey<Level>, ScanJob> entry = it.next();
            ScanJob job = entry.getValue();
            while (job.remaining.hasNext()) {
                FinderTarget target = StructureFinderService.nearest(job.level, job.origin, job.remaining.next());
                if (target != null) {
                    job.found.add(target);
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (!job.remaining.hasNext()) {
                job.found.sort(Comparator.comparingDouble(FinderTarget::distanceSq));
                CACHES.put(entry.getKey(), new Cache(job.structureVersion, job.found));
                it.remove();
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /** Derives a player's display from their current dimension's cache; cheap, so it runs every tick. */
    private static void publish(StructureFinderState state, ServerPlayer player) {
        UUID uuid = player.getUUID();
        int tier = StructureFinderService.tier(player);
        Cache cache = CACHES.get(player.level().dimension());
        if (tier <= 0 || cache == null) {
            if (PUBLISHED.remove(uuid) != null) {
                state.remove(uuid);
            }
            return;
        }
        Published published = PUBLISHED.get(uuid);
        if (published != null && published.tier() == tier && published.cache() == cache) {
            return;  // tier and cached scan both unchanged since the last publish
        }
        List<FinderTarget> full = cache.full();
        int keep = StructureFinderService.cap(tier, full.size());
        List<FinderTarget> targets = keep >= full.size() ? full : List.copyOf(full.subList(0, keep));
        state.putSnapshot(uuid, new Snapshot(tier, targets));
        PUBLISHED.put(uuid, new Published(tier, cache));
    }

    private static int structureVersion() {
        return AEM.ARCHIPELAGO.client().registries().apStructures().unlockVersion();
    }

    private static boolean finderActive() {
        return AEM.ARCHIPELAGO.client().state().parsedSlotData().structureFinderActive();
    }

    /** A dimension level and the origin within it that a scan searches from. */
    private record ScanAnchor(ServerLevel level, BlockPos origin) {}

    /** A dimension's completed scan and the structure-unlock version that produced it. */
    private record Cache(int structureVersion, List<FinderTarget> full) {}

    /** The tier and cache identity of the last published snapshot, to skip redundant publishes. */
    private record Published(int tier, Cache cache) {}

    /**
     * A dimension scan in progress: the version it started at, the level and origin it searches from,
     * the structure types still to search, and the nearest instances found so far.
     */
    private static final class ScanJob {
        private final int structureVersion;
        private final ServerLevel level;
        private final BlockPos origin;
        private final Iterator<Holder.Reference<Structure>> remaining;
        private final List<FinderTarget> found = new ArrayList<>();

        ScanJob(int structureVersion, ServerLevel level, BlockPos origin,
                Iterator<Holder.Reference<Structure>> remaining) {
            this.structureVersion = structureVersion;
            this.level = level;
            this.origin = origin;
            this.remaining = remaining;
        }
    }
}
