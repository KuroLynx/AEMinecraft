package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Paces the traps out, one at a time.
 *
 * <p>Traps do not arrive politely. Another world finishing its game releases its whole pool at once,
 * and every trap in it lands in the same tick: the victim eats a TNT, a creeper, an inventory shuffle
 * and an MLG simultaneously, which is not several traps but one unsurvivable event with no counterplay
 * — and the MLG in particular will simply drop the player out of the sky mid-explosion.
 *
 * <p>So traps queue here and are released no closer than {@value #MIN_SPACING_SECONDS}–{@value
 * #MAX_SPACING_SECONDS} seconds apart (rolled per trap, so the run does not tick like a metronome).
 * Each one lands on a player who has had time to recover from the last.
 *
 * <p>Nothing is released while the victim is inside their {@link SpawnGraceService} grace: a trap that
 * went off against the arrival shield would be spent for nothing, so it simply waits its turn.
 *
 * <p>A player who logs out drops their queue. They were present when those traps landed, which is why
 * they were queued at all, but a trap is a moment in the world — banking it for a session they have
 * not started yet turns it back into the ambush-on-arrival that {@link FillerTrapService} refuses to
 * deliver.
 */
public final class TrapScheduler {
    /** Shortest gap between two traps hitting the same player. */
    public static final int MIN_SPACING_SECONDS = 30;
    /** Longest gap; the actual delay is rolled per trap between the two. */
    public static final int MAX_SPACING_SECONDS = 60;
    /**
     * How many traps may be waiting per player. A mass release can send dozens, and at one per
     * three-quarters of a minute that is an afternoon of being blown up; past this the surplus is
     * dropped rather than queued into a punishment with no end.
     */
    private static final int MAX_QUEUED = 5;

    /** Player uuid -> the trap effect keys waiting to fire, in arrival order. Server-thread only. */
    private static final Map<UUID, Deque<String>> QUEUES = new HashMap<>();
    /** Player uuid -> the earliest time their next trap may fire. */
    private static final Map<UUID, Long> NEXT_ALLOWED = new HashMap<>();

    private TrapScheduler() {}

    /** Registers the release tick. Call once during server-bridge setup. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TrapScheduler::tick);
    }

    /** Queues one trap for {@code player}; it fires as soon as the spacing (and their grace) allows. */
    public static void submit(ServerPlayer player, String effectKey) {
        Deque<String> queue = QUEUES.computeIfAbsent(player.getUUID(), uuid -> new ArrayDeque<>());
        if (queue.size() >= MAX_QUEUED) {
            AEM.LOGGER.info("Trap '{}' dropped: {} already has {} waiting",
                    effectKey, player.getGameProfile().name(), queue.size());
            return;
        }
        queue.addLast(effectKey);
    }

    /** Forgets a leaving player's queue and spacing. */
    public static void onPlayerLeave(ServerPlayer player) {
        QUEUES.remove(player.getUUID());
        NEXT_ALLOWED.remove(player.getUUID());
    }

    private static void tick(MinecraftServer server) {
        if (QUEUES.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Deque<String>>> it = QUEUES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Deque<String>> entry = it.next();
            Deque<String> queue = entry.getValue();
            if (queue.isEmpty()) {
                it.remove();
                NEXT_ALLOWED.remove(entry.getKey());  // the two maps live and die together
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();  // logged out; see the class note on why the queue goes with them
                NEXT_ALLOWED.remove(entry.getKey());
                continue;
            }
            Long allowedAt = NEXT_ALLOWED.get(entry.getKey());
            if ((allowedAt != null && now < allowedAt) || SpawnGraceService.isProtected(player)) {
                continue;
            }
            TrapEffects.run(queue.removeFirst(), player);
            NEXT_ALLOWED.put(entry.getKey(), now + rollSpacing(server));
        }
    }

    /** A fresh gap in the configured range, in milliseconds. */
    private static long rollSpacing(MinecraftServer server) {
        int span = MAX_SPACING_SECONDS - MIN_SPACING_SECONDS;
        return (MIN_SPACING_SECONDS + server.overworld().getRandom().nextInt(span + 1)) * 1000L;
    }
}
