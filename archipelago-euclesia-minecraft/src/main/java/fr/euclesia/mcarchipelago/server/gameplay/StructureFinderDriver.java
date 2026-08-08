package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.net.APStateSync;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Drives the Structure-Finder search and publishes results to {@link StructureFinderState} for the
 * client to render.
 *
 * <p>Two things move at very different speeds here, and the split between them is the whole design:
 *
 * <ul>
 *   <li><b>Locating</b> is heavy — one {@code findNearestMapStructure} ({@code /locate}-style worldgen
 *       scan) per eligible structure, and a single rare structure can scan the whole radius on its own.
 *       So it runs as a <em>sweep</em>: the structure types are searched a few per tick under a shared
 *       budget ({@link #TICK_BUDGET_NANOS}), and the previous result stays on screen until the new one
 *       is complete. A sweep is per player, anchored where that player stood when it started — but a
 *       player standing near someone whose sweep already finished adopts that result rather than
 *       re-searching the same ground, so a party costs about what one player costs.</li>
 *   <li><b>Choosing what to show</b> is cheap — sort the located structures by how far they are from
 *       the player <em>right now</em> and keep as many as the finder tier reveals. This runs off the
 *       sweep entirely, every {@link #SELECT_INTERVAL_TICKS} ticks, so at tier 1 the bar holds the five
 *       structures nearest to you as you walk rather than the five nearest to wherever the scan was
 *       anchored.</li>
 * </ul>
 *
 * <p>A new sweep starts when the player has travelled {@value #RESWEEP_DISTANCE} blocks from the anchor
 * of their current result, when they change dimension, or when a structure unlock changes what is
 * findable. Standing still costs nothing: structures do not move, so there is no timed refresh.
 * A sweep already in flight is never restarted by movement alone — a player on an elytra would
 * otherwise keep resetting it and never get a result at all.
 *
 * <p>Structures that came back <em>absent</em> (nothing within the search radius) are the expensive
 * ones — they cost a full-radius scan to prove — so a sweep carries the previous sweep's absentees and
 * skips them, until the anchor has moved {@value #ABSENT_CARRY_DISTANCE} blocks from where absence was
 * last actually established. Frequent sweeps while travelling therefore stay cheap, and the full
 * search still comes back around.
 *
 * <p>The starting dimension is swept once at server start, all at once, while the world-loading screen
 * is still up (the integrated server only accepts the client once startup finishes). A player spawning
 * there adopts that result, so the finder is already located on the first frame — no in-world stutter.
 * The start dimension follows the {@code start_dimension} slot option (overworld → world spawn; nether
 * → the scaled spawn the player is dropped near). By this point the Archipelago pre-flight connect has
 * completed (it gates {@code doWorldLoad}), so the slot data needed to scan is available.
 */
public final class StructureFinderDriver {
    /** Upper bound on time spent advancing sweeps each tick (~1.5 ms), shared across all players. */
    private static final long TICK_BUDGET_NANOS = 1_500_000L;
    /** How far a player must travel from their result's anchor before a fresh sweep starts. */
    private static final int RESWEEP_DISTANCE = 256;
    /** How far the anchor may drift before absent structures are searched for again rather than skipped. */
    private static final int ABSENT_CARRY_DISTANCE = 800;
    /** How often the displayed subset is re-picked against the live player position (~0.5 s). */
    private static final int SELECT_INTERVAL_TICKS = 10;
    /** Slot value of {@code start_dimension} for a Nether start. */
    private static final String NETHER_START = "nether";
    /** Y used to anchor the Nether scan; structure location is effectively horizontal, so it is nominal. */
    private static final int NETHER_ANCHOR_Y = 64;

    /** The start-of-server sweep, adopted by players who spawn near where it was anchored. */
    private static final Map<ResourceKey<Level>, Result> SEEDS = new HashMap<>();
    /** Per-player sweep state and results. Server-thread only. */
    private static final Map<UUID, PlayerFinder> FINDERS = new HashMap<>();

    private static int tickCounter;
    /** Rotates which player's sweep gets the tick budget first; see {@link #advanceSweeps}. */
    private static int sweepCursor;

    private StructureFinderDriver() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(StructureFinderDriver::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(StructureFinderDriver::onEndTick);
    }

    /**
     * Sweep the starting dimension while the world is still loading. Runs all at once (no per-tick
     * budget) on purpose: the integrated server has not yet accepted the client, so this time is spent
     * on the loading screen rather than as an in-world freeze.
     */
    private static void onServerStarted(MinecraftServer server) {
        reset();
        if (!AEMServerRuntime.isArchipelagoReady() || !finderActive()) {
            return;
        }
        ScanAnchor anchor = startAnchor(server);
        if (anchor == null) {
            return;
        }
        int structureVersion = structureVersion();
        List<FinderTarget> found = new ArrayList<>();
        Set<Holder.Reference<Structure>> absent = new HashSet<>();
        for (Holder.Reference<Structure> ref : StructureFinderService.candidates(anchor.level())) {
            FinderTarget target = StructureFinderService.nearest(anchor.level(), anchor.origin(), ref);
            if (target != null) {
                found.add(target);
            } else {
                absent.add(ref);
            }
        }
        SEEDS.put(anchor.level().dimension(),
                new Result(structureVersion, anchor.origin(), List.copyOf(found), anchor.origin(), absent));
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
            reset();
            return;
        }
        StructureFinderState state = StructureFinderState.get();
        int structureVersion = structureVersion();
        int tier = StructureFinderService.tier();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        boolean reselect = ++tickCounter % SELECT_INTERVAL_TICKS == 0;

        if (finderActive() && tier > 0) {
            for (ServerPlayer player : players) {
                ensureSweep(player, structureVersion);
            }
            advanceSweeps();
        }
        for (ServerPlayer player : players) {
            publish(state, player, tier, reselect);
        }
    }

    /**
     * Starts a sweep for the player if their current result is missing, out of date, or anchored too
     * far behind them. A sweep already running for this dimension and structure version is left alone
     * — restarting it every time the player moves would mean it never finishes.
     */
    private static void ensureSweep(ServerPlayer player, int structureVersion) {
        PlayerFinder finder = FINDERS.computeIfAbsent(player.getUUID(), uuid -> new PlayerFinder());
        ServerLevel level = player.level();
        ResourceKey<Level> dimension = level.dimension();
        Sweep sweep = finder.sweep;
        if (sweep != null) {
            if (sweep.level == level && sweep.structureVersion == structureVersion) {
                return;  // in flight and still valid; let it finish
            }
            finder.sweep = null;  // the player changed dimension, or a structure unlocked
        }
        BlockPos at = player.blockPosition();
        Result result = finder.results.get(dimension);
        if (result == null) {
            // First time here: a start-of-server sweep of this dimension, if the player is standing
            // where it was anchored, spares them a budgeted in-world one.
            Result seed = SEEDS.get(dimension);
            if (seed != null && seed.structureVersion() == structureVersion && withinResweep(seed.origin(), at)) {
                finder.results.put(dimension, seed);
                return;
            }
        } else if (result.structureVersion() == structureVersion && withinResweep(result.origin(), at)) {
            return;  // still fresh, and anchored close enough to where the player is
        }
        Result shared = nearbyResult(player.getUUID(), dimension, structureVersion, at);
        if (shared != null) {
            finder.results.put(dimension, shared);
            return;
        }
        finder.sweep = startSweep(structureVersion, level, at, result);
    }

    /**
     * A sweep another player already finished that is just as good for this one: same dimension, same
     * unlock version, anchored close enough that it passes the freshness test they are about to fail.
     *
     * <p>Two players exploring together were each paying a full worldgen sweep over the same ground,
     * out of one shared tick budget — so a party halved its own refresh rate for identical results, and
     * a group on elytras could re-trigger sweeps faster than the budget could finish them. Sharing the
     * finished one costs nothing: it is the same anchor either of them would have searched from.
     */
    private static Result nearbyResult(UUID self, ResourceKey<Level> dimension, int structureVersion,
                                       BlockPos at) {
        for (Map.Entry<UUID, PlayerFinder> entry : FINDERS.entrySet()) {
            if (entry.getKey().equals(self)) {
                continue;
            }
            Result other = entry.getValue().results.get(dimension);
            if (other != null && other.structureVersion() == structureVersion
                    && withinResweep(other.origin(), at)) {
                return other;
            }
        }
        return null;
    }

    private static boolean withinResweep(BlockPos anchor, BlockPos at) {
        return anchor.distSqr(at) <= (double) RESWEEP_DISTANCE * RESWEEP_DISTANCE;
    }

    /**
     * Builds a sweep, carrying forward the structures the previous one proved absent so their
     * full-radius searches are not paid for again on every short hop. The carry lapses once the anchor
     * has drifted {@value #ABSENT_CARRY_DISTANCE} blocks from where absence was established.
     */
    private static Sweep startSweep(int structureVersion, ServerLevel level, BlockPos origin, Result previous) {
        Set<Holder.Reference<Structure>> absent = new HashSet<>();
        BlockPos absentOrigin = origin;
        if (previous != null && previous.structureVersion() == structureVersion
                && previous.absentOrigin().distSqr(origin)
                        <= (double) ABSENT_CARRY_DISTANCE * ABSENT_CARRY_DISTANCE) {
            absent.addAll(previous.absent());
            absentOrigin = previous.absentOrigin();
        }
        List<Holder.Reference<Structure>> todo = new ArrayList<>();
        for (Holder.Reference<Structure> ref : StructureFinderService.candidates(level)) {
            if (!absent.contains(ref)) {
                todo.add(ref);
            }
        }
        return new Sweep(structureVersion, level, origin, absentOrigin, absent, todo.iterator());
    }

    /**
     * Advances in-flight sweeps, sharing one time budget across them; stores each as it finishes.
     *
     * <p>The budget usually runs out partway through, so the sweeps are visited from a rotating start:
     * a fixed order would spend every tick on whoever came first in the map and leave a second player's
     * sweep to never advance at all.
     */
    private static void advanceSweeps() {
        List<PlayerFinder> sweeping = new ArrayList<>();
        for (PlayerFinder finder : FINDERS.values()) {
            if (finder.sweep != null) {
                sweeping.add(finder);
            }
        }
        if (sweeping.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        int start = Math.floorMod(sweepCursor++, sweeping.size());
        for (int i = 0; i < sweeping.size(); i++) {
            PlayerFinder finder = sweeping.get((start + i) % sweeping.size());
            Sweep sweep = finder.sweep;
            while (sweep.remaining.hasNext()) {
                Holder.Reference<Structure> ref = sweep.remaining.next();
                FinderTarget target = StructureFinderService.nearest(sweep.level, sweep.origin, ref);
                if (target != null) {
                    sweep.found.add(target);
                } else {
                    sweep.absent.add(ref);  // nothing in radius: skip it on the next few sweeps
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (!sweep.remaining.hasNext()) {
                finder.results.put(sweep.level.dimension(),
                        new Result(sweep.structureVersion, sweep.origin, List.copyOf(sweep.found),
                                sweep.absentOrigin, sweep.absent));
                finder.sweep = null;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /**
     * Picks the structures a player should see out of their located set and pushes them if that has
     * changed. The pick is by distance from the player's <em>current</em> position, not from the sweep
     * anchor, so the tier-1 bar really is "the nearest five to me" wherever the sweep happened to start.
     */
    private static void publish(StructureFinderState state, ServerPlayer player, int tier, boolean reselect) {
        UUID uuid = player.getUUID();
        PlayerFinder finder = FINDERS.get(uuid);
        Result result = finder == null ? null : finder.results.get(player.level().dimension());
        if (tier <= 0 || result == null) {
            if (finder != null && finder.published != null) {
                finder.published = null;
                state.remove(uuid);
                // Tell the client too, or its bar keeps showing the last thing it was sent.
                APStateSync.sendFinder(player, 0, List.of());
            }
            return;
        }
        Published published = finder.published;
        boolean stale = published == null || published.tier() != tier || published.result() != result;
        if (!stale && !reselect) {
            return;  // the same pick as last tick, and it is not time to re-check the distances
        }

        List<FinderTarget> targets = select(result.full(), tier, player.blockPosition());
        if (published != null && published.tier() == tier && sameTargets(published.targets(), targets)) {
            // The pick did not actually change (the player moved a little, or a fresh sweep landed on
            // the same structures). Note the current source so this stops recomputing every tick.
            finder.published = new Published(tier, result, published.targets());
            return;
        }
        state.putSnapshot(uuid, new Snapshot(tier, targets));
        // Singleplayer reads the snapshot above straight out of this holder; a remote client has no
        // such holder to read, so the same snapshot goes down the wire.
        APStateSync.sendFinder(player, tier, targets);
        finder.published = new Published(tier, result, targets);
    }

    /** The {@code tier}'s worth of structures nearest to {@code at}, nearest first. */
    private static List<FinderTarget> select(List<FinderTarget> full, int tier, BlockPos at) {
        int keep = StructureFinderService.cap(tier, full.size());
        if (keep <= 0) {
            return List.of();
        }
        List<FinderTarget> byDistance = new ArrayList<>(full.size());
        for (FinderTarget target : full) {
            byDistance.add(new FinderTarget(target.structureId(), target.pos(), at.distSqr(target.pos())));
        }
        byDistance.sort(Comparator.comparingDouble(FinderTarget::distanceSq));
        return keep >= byDistance.size() ? List.copyOf(byDistance) : List.copyOf(byDistance.subList(0, keep));
    }

    /**
     * Whether two picks would draw the same bar. Compares the structures and their positions but not
     * their distances, which change with every step and would make every re-pick look like a change.
     */
    private static boolean sameTargets(List<FinderTarget> a, List<FinderTarget> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).structureId().equals(b.get(i).structureId())
                    || !a.get(i).pos().equals(b.get(i).pos())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Forgets a leaving player's sweep and results.
     *
     * <p>The published record exists to skip re-sending an unchanged bar, and it is keyed by uuid, so
     * without this it outlives the player: they disconnect, their client wipes its copy of the
     * snapshot, they reconnect — and the server still believes it has already told them. Dropping the
     * record here makes the next tick treat them as new.
     */
    public static void onPlayerLeave(ServerPlayer player) {
        FINDERS.remove(player.getUUID());
        StructureFinderState.get().remove(player.getUUID());
    }

    private static void reset() {
        SEEDS.clear();
        FINDERS.clear();
    }

    private static int structureVersion() {
        return AEM.ARCHIPELAGO.client().registries().apStructures().unlockVersion();
    }

    private static boolean finderActive() {
        return AEM.ARCHIPELAGO.client().state().parsedSlotData().structureFinderActive();
    }

    /** A dimension level and the origin within it that a scan searches from. */
    private record ScanAnchor(ServerLevel level, BlockPos origin) {}

    /**
     * A completed sweep: the structure-unlock version and origin it ran at, everything it located, and
     * the structures it found nothing of (with the origin that proved it, which may predate this sweep
     * when the absentees were carried forward).
     */
    private record Result(int structureVersion, BlockPos origin, List<FinderTarget> full,
                          BlockPos absentOrigin, Set<Holder.Reference<Structure>> absent) {}

    /** The tier, source result and exact targets last published, to skip redundant sends. */
    private record Published(int tier, Result result, List<FinderTarget> targets) {}

    /** One player's finder: a sweep in flight, the completed result per dimension, and what was sent. */
    private static final class PlayerFinder {
        private final Map<ResourceKey<Level>, Result> results = new HashMap<>();
        private Sweep sweep;
        private Published published;
    }

    /**
     * A sweep in progress: the version it started at, the level and origin it searches from, the
     * structure types still to search, what it has located, and the absentees it will hand on.
     */
    private static final class Sweep {
        private final int structureVersion;
        private final ServerLevel level;
        private final BlockPos origin;
        private final BlockPos absentOrigin;
        private final Set<Holder.Reference<Structure>> absent;
        private final Iterator<Holder.Reference<Structure>> remaining;
        private final List<FinderTarget> found = new ArrayList<>();

        Sweep(int structureVersion, ServerLevel level, BlockPos origin, BlockPos absentOrigin,
              Set<Holder.Reference<Structure>> absent, Iterator<Holder.Reference<Structure>> remaining) {
            this.structureVersion = structureVersion;
            this.level = level;
            this.origin = origin;
            this.absentOrigin = absentOrigin;
            this.absent = absent;
            this.remaining = remaining;
        }
    }
}
