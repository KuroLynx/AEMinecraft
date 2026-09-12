package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureData.CapturedPlacement;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Places unlocked structures a few at a time instead of all at once.
 *
 * <p>One unlock item is one structure type, and applying its captured placements immediately was
 * fine. A mass unlock is not: when another player finishes their game and releases, this slot can
 * receive dozens of Structure Unlock items in a single batch, and every captured placement of every
 * one of those types — across the whole area anyone has explored — was written in one synchronous
 * pass on the server thread. That is potentially hundreds of thousands of block writes inside a
 * single tick: the server stalls, the watchdog may kill it, and from a player's seat the structures
 * simply never appear.
 *
 * <p>So placements queue here and drain under a per-tick budget. The world fills in over a few
 * seconds instead of the server stopping dead.
 *
 * <p>The queue holds placements that have already been removed from their saved data, so anything
 * still pending at shutdown is written back rather than lost — a structure half-restored across a
 * restart would be unrecoverable, and this is exactly the mass case where a restart is most likely.
 */
public final class StructurePlacementQueue {
    /** Time per tick spent placing (~2 ms). Deliberately below the finder's budget: this runs in bursts. */
    private static final long TICK_BUDGET_NANOS = 2_000_000L;

    /**
     * One unit of work. Two kinds share the queue and the budget:
     *
     * <ul>
     *   <li>a REBUILD — a structure to regenerate and place chunk by chunk
     *       ({@link StructureReplayService}), which is what every new unlock produces;
     *   <li>a LEGACY placement — captured blocks from a world written before the rebuild path
     *       existed, applied verbatim as they always were.
     * </ul>
     *
     * A rebuild is created lazily: the start is generated on the tick the job first comes up, then
     * its chunks are placed a few per tick, so a mass unlock never generates dozens of starts at once.
     */
    private sealed interface Pending {
        ServerLevel level();
        String structureId();
    }

    private record Legacy(ServerLevel level, String structureId, CapturedPlacement placement)
            implements Pending {}

    private static final class Rebuild implements Pending {
        private final ServerLevel level;
        private final String structureId;
        private final ChunkPos origin;
        private StructureStart start;          // null until this job's first turn
        private List<ChunkPos> remaining;      // chunks still to place
        private boolean dead;                  // the start no longer generates here

        Rebuild(ServerLevel level, String structureId, ChunkPos origin) {
            this.level = level;
            this.structureId = structureId;
            this.origin = origin;
        }

        @Override public ServerLevel level() { return level; }
        @Override public String structureId() { return structureId; }
        ChunkPos origin() { return origin; }

        /** Places the next chunk; true while this job still has work. */
        boolean step() {
            if (start == null) {
                Optional<StructureStart> generated =
                        StructureReplayService.regenerate(level, structureId, origin);
                if (generated.isEmpty()) {
                    dead = true;
                    return false;
                }
                start = generated.get();
                remaining = new ArrayList<>(StructureReplayService.chunksOf(start));
            }
            if (remaining.isEmpty()) {
                return false;
            }
            StructureReplayService.placeChunk(level, start, remaining.remove(0));
            return !remaining.isEmpty();
        }

        boolean isDead() { return dead; }
    }

    private static final Deque<Pending> QUEUE = new ArrayDeque<>();

    private StructurePlacementQueue() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> drain());
        // Anything still queued goes back to the store, so a restart resumes rather than loses it.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> returnToStore());
    }

    /** Queues one legacy captured placement for application. Server thread. */
    public static void enqueue(ServerLevel level, String structureId, CapturedPlacement placement) {
        QUEUE.add(new Legacy(level, structureId, placement));
    }

    /** Queues one suppressed instance to be regenerated and placed. Server thread. */
    public static void enqueueRebuild(ServerLevel level, String structureId, ChunkPos origin) {
        QUEUE.add(new Rebuild(level, structureId, origin));
    }

    public static int pending() {
        return QUEUE.size();
    }

    private static void drain() {
        if (QUEUE.isEmpty()) {
            return;
        }
        int placed = 0;
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        // At least one per tick even if the budget is already blown, so a queue always makes progress.
        do {
            Pending next = QUEUE.poll();
            if (next == null) {
                break;
            }
            if (next instanceof Legacy legacy) {
                StructureCaptureService.applyPlacementNow(legacy.level(), legacy.placement());
            } else if (next instanceof Rebuild rebuild && rebuild.step()) {
                // More chunks to go: back to the front, so one structure finishes before the next
                // starts and a half-built one is never left sitting behind a queue of others.
                QUEUE.addFirst(rebuild);
            }
            placed++;
        } while (!QUEUE.isEmpty() && System.nanoTime() < deadline);

        if (QUEUE.isEmpty()) {
            AEMDebug.log("structurePlacementQueue drained (last batch {} placements)", placed);
        }
    }

    /** Puts everything still queued back where it came from. */
    private static void returnToStore() {
        if (QUEUE.isEmpty()) {
            return;
        }
        AEM.LOGGER.info("Returning {} unplaced structure placement(s) to storage for next start.",
                QUEUE.size());
        Pending next;
        while ((next = QUEUE.poll()) != null) {
            if (next instanceof Legacy legacy) {
                StructureCaptureService.restoreCaptured(legacy.level(), legacy.structureId(),
                        legacy.placement());
            } else if (next instanceof Rebuild rebuild && !rebuild.isDead()) {
                // Re-recorded whole: a rebuild is idempotent, so replaying the chunks it already
                // wrote costs nothing but a repeat, and half a structure across a restart would be
                // unrecoverable.
                StructureReplayService.restoreSuppressed(rebuild.level(), rebuild.structureId(),
                        rebuild.origin());
            }
        }
    }
}
