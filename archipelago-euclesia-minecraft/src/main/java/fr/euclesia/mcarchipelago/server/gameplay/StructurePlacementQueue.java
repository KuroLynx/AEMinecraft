package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureData.CapturedPlacement;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.Deque;

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

    private record Pending(ServerLevel level, String structureId, CapturedPlacement placement) {}

    private static final Deque<Pending> QUEUE = new ArrayDeque<>();

    private StructurePlacementQueue() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> drain());
        // Anything still queued goes back to the store, so a restart resumes rather than loses it.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> returnToStore());
    }

    /** Queues one captured placement for application. Server thread. */
    public static void enqueue(ServerLevel level, String structureId, CapturedPlacement placement) {
        QUEUE.add(new Pending(level, structureId, placement));
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
            StructureCaptureService.applyPlacementNow(next.level(), next.placement());
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
            StructureCaptureService.restoreCaptured(next.level(), next.structureId(), next.placement());
        }
    }
}
