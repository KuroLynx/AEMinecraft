package fr.euclesia.mcarchipelago.server.gameplay;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A minute of safety on arriving in the world.
 *
 * <p>Walking in should not be fatal. A player entering the world has no idea what is around them —
 * the mob that wandered onto their bed while they were away, the lava the last session left burning,
 * the pile of traps the run banked in their absence — and dying to any of it before the terrain has
 * even finished drawing is a death nobody could have played around.
 *
 * <p>So for {@value #GRACE_SECONDS} seconds after joining or respawning, a player takes no damage.
 * The one exception is anything tagged {@code bypasses_invulnerability} — {@code /kill}, the void, and
 * the DeathLink kill that rides on it. Those are not arrival hazards; they are somebody deciding this
 * player dies, and grace is not a veto on that.
 *
 * <p>Traps are held rather than wasted. A trap that lands while the victim is still protected would
 * otherwise pop harmlessly against the shield and be gone, so {@link TrapScheduler} keeps it queued
 * until the grace runs out — the player gets their minute, and the run still gets to spring the trap.
 */
public final class SpawnGraceService {
    /** How long a player is protected after entering the world. */
    public static final int GRACE_SECONDS = 60;
    private static final long GRACE_MS = GRACE_SECONDS * 1000L;

    /** Player uuid -> when their protection ends. Server-thread only. */
    private static final Map<UUID, Long> PROTECTED = new HashMap<>();

    private SpawnGraceService() {}

    /** Registers the expiry tick. Call once during server-bridge setup. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SpawnGraceService::tick);
    }

    /** Starts (or restarts) a player's grace. Called on join and on respawn. */
    public static void begin(ServerPlayer player) {
        PROTECTED.put(player.getUUID(), System.currentTimeMillis() + GRACE_MS);
        player.sendSystemMessage(Component.translatable("message.aem.grace.start", GRACE_SECONDS));
    }

    /** Whether {@code player} is currently within their arrival grace. */
    public static boolean isProtected(ServerPlayer player) {
        Long until = PROTECTED.get(player.getUUID());
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Ends a leaving player's grace. Without this the record outlives them, and a player who logs
     * back in after it lapsed would be treated as protected until the next tick noticed.
     */
    public static void onPlayerLeave(ServerPlayer player) {
        PROTECTED.remove(player.getUUID());
    }

    private static void tick(MinecraftServer server) {
        if (PROTECTED.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = PROTECTED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now < entry.getValue()) {
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;  // logged out mid-grace; nothing to tell, and their queue left with them
            }
            player.sendSystemMessage(Component.translatable("message.aem.grace.end"));
            // Anything the run banked meanwhile is already queued; TrapScheduler releases it now
            // that this player can be touched again.
        }
    }
}
