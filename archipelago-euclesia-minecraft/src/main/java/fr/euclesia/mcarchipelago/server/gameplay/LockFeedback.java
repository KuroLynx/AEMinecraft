package fr.euclesia.mcarchipelago.server.gameplay;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a player why a gated action just failed. The lock services (material/tool pickup, station
 * use) only block silently; this prints a short red chat line so the player knows what they're
 * missing. Repeated blocked attempts (e.g. spam-clicking a locked ore) are throttled per-player so
 * the chat doesn't flood.
 */
public final class LockFeedback {
    /** Minimum gap between feedback lines for a given player. */
    private static final long COOLDOWN_MS = 2000L;

    private static final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

    private LockFeedback() {}

    /** Sends {@code reason} to {@code player} as a chat message, at most once per {@link #COOLDOWN_MS}. */
    public static void notify(ServerPlayer player, Component reason) {
        if (player == null || reason == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastSent.get(player.getUUID());
        if (previous != null && now - previous < COOLDOWN_MS) {
            return;
        }
        lastSent.put(player.getUUID(), now);
        player.sendSystemMessage(reason);
    }
}
