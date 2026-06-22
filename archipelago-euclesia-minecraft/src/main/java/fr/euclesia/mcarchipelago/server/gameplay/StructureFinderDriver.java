package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderState.Snapshot;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
 * Drives the Structure-Finder search on the server tick and publishes results to
 * {@link StructureFinderState} for the client to render. The search is heavy (one
 * {@code findNearestMapStructure} per eligible structure), so it recomputes only when the result
 * could actually change: new items received (the finder tier rose, or a structure unlocked), a
 * dimension change, or the player travelled far enough that "nearest" might differ — and
 * movement-driven recomputes are rate-limited.
 *
 * <p>A recompute does not run all at once: scanning every structure type in a single tick blocks
 * the server thread long enough to freeze the game (most visible the moment a finder is received and
 * the first full scan kicks off). Instead each player's scan is a {@link ScanJob} advanced under a
 * per-tick time budget ({@link #TICK_BUDGET_NANOS}) across as many ticks as it takes; the snapshot
 * is published only once the job completes.
 *
 * <p>The number of structures published grows with the finder tier (see
 * {@link StructureFinderService#cap}): more copies reveal more of the surrounding structures.
 */
public final class StructureFinderDriver {
    /** Horizontal distance (blocks) a player must travel before a movement-driven recompute. */
    private static final double RECOMPUTE_DISTANCE = 80.0;
    /** Minimum server ticks between movement-driven recomputes (caps cost while travelling). */
    private static final int MOVE_RECOMPUTE_GAP = 40;
    /** Upper bound on time spent advancing a single player's scan each tick (~1.5 ms). */
    private static final long TICK_BUDGET_NANOS = 1_500_000L;

    /** Inputs that produced each player's last published snapshot. Server-thread only. */
    private static final Map<UUID, Context> CONTEXTS = new HashMap<>();
    /** In-flight scans, one per player while recomputing. Server-thread only. */
    private static final Map<UUID, ScanJob> JOBS = new HashMap<>();

    private StructureFinderDriver() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(StructureFinderDriver::onEndTick);
    }

    private static void onEndTick(MinecraftServer server) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            CONTEXTS.clear();
            JOBS.clear();
            return;
        }
        StructureFinderState state = StructureFinderState.get();
        int tickCount = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updatePlayer(state, player, tickCount);
        }
    }

    private static void updatePlayer(StructureFinderState state, ServerPlayer player, int tickCount) {
        UUID uuid = player.getUUID();
        int tier = StructureFinderService.tier(player);
        if (tier <= 0) {
            // No finder yet: drop anything left from a previous state.
            JOBS.remove(uuid);
            if (CONTEXTS.remove(uuid) != null) {
                state.remove(uuid);
            }
            return;
        }

        int version = AEM.ARCHIPELAGO.client().registries().apItems().receivedVersion();
        ResourceKey<Level> dimension = player.level().dimension();
        BlockPos origin = player.blockPosition();

        ScanJob job = JOBS.get(uuid);
        if (job != null && job.isStale(tier, version, dimension, origin)) {
            job = null;  // inputs moved on; abandon and restart below from the current state
            JOBS.remove(uuid);
        }
        if (job == null) {
            Context ctx = CONTEXTS.get(uuid);
            boolean recompute =
                    ctx == null
                    || ctx.tier() != tier
                    || ctx.version() != version
                    || !dimension.equals(ctx.dimension())
                    || (movedFar(ctx.origin(), origin) && tickCount - ctx.computeTick() >= MOVE_RECOMPUTE_GAP);
            if (!recompute) {
                return;
            }
            job = new ScanJob(tier, version, dimension, origin,
                    StructureFinderService.candidates(player).iterator());
            JOBS.put(uuid, job);
        }

        // Advance this scan for at most the tick budget; at least one structure is searched per tick
        // (the deadline is checked after each), so even a heavy world makes steady progress.
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        while (job.remaining.hasNext()) {
            FinderTarget target = StructureFinderService.nearest(player, origin, job.remaining.next());
            if (target != null) {
                job.found.add(target);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }

        if (job.remaining.hasNext()) {
            return;  // more types to scan on a later tick; nothing published yet
        }

        // Scan complete: one nearest instance per unlocked type, nearest first; tier decides how many.
        job.found.sort(Comparator.comparingDouble(FinderTarget::distanceSq));
        int keep = StructureFinderService.cap(tier, job.found.size());
        List<FinderTarget> targets =
                keep >= job.found.size() ? job.found : new ArrayList<>(job.found.subList(0, keep));
        state.putSnapshot(uuid, new Snapshot(tier, targets));
        CONTEXTS.put(uuid, new Context(tier, version, dimension, origin, tickCount));
        JOBS.remove(uuid);
    }

    private static boolean movedFar(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz >= RECOMPUTE_DISTANCE * RECOMPUTE_DISTANCE;
    }

    private record Context(int tier, int version, ResourceKey<Level> dimension,
                           BlockPos origin, int computeTick) {}

    /**
     * A structure scan in progress for one player: the inputs it started from, the structure types
     * still to search, and the nearest instances found so far. Restarted (see {@link #isStale}) if
     * the tier, received version, dimension, or origin drifts mid-scan so stale results are never
     * published.
     */
    private static final class ScanJob {
        private final int tier;
        private final int version;
        private final ResourceKey<Level> dimension;
        private final BlockPos origin;
        private final Iterator<Holder.Reference<Structure>> remaining;
        private final List<FinderTarget> found = new ArrayList<>();

        ScanJob(int tier, int version, ResourceKey<Level> dimension, BlockPos origin,
                Iterator<Holder.Reference<Structure>> remaining) {
            this.tier = tier;
            this.version = version;
            this.dimension = dimension;
            this.origin = origin;
            this.remaining = remaining;
        }

        boolean isStale(int tier, int version, ResourceKey<Level> dimension, BlockPos origin) {
            return this.tier != tier
                    || this.version != version
                    || !this.dimension.equals(dimension)
                    || movedFar(this.origin, origin);
        }
    }
}
