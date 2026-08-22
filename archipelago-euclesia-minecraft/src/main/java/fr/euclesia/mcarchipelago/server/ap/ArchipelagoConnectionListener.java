package fr.euclesia.mcarchipelago.server.ap;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.runtime.APSlotGate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Decides what a dropped Archipelago session means for the players in the world.
 *
 * <p>It used to mean the end of the session: everyone was kicked, because the locks could not tell
 * locked content from free without live slot data, and a world running blind places structures the
 * slot meant to hold back — permanently. The kick was protecting the world, not enforcing a rule.
 *
 * <p>A world that has connected once no longer runs blind. Its slot data is cached
 * ({@link fr.euclesia.mcarchipelago.server.session.APSessionCache}) and the gates read the cache
 * exactly as they read a live session, so the drop costs only the two things that genuinely need the
 * socket: incoming items, and outgoing checks. Checks are queued and sent on reconnect; items simply
 * do not arrive until then. So the run continues, and the players are told what changed rather than
 * removed from it.
 *
 * <p>The kick remains for the one case that never got its data: a session that drops before the cache
 * exists. There the world really is blind, and putting people out is still the only safe answer.
 *
 * <p>Does nothing when no server is active — including the expected close fired while leaving a world,
 * since {@code SERVER_STOPPING} clears the server reference before closing the link.
 */
public final class ArchipelagoConnectionListener implements APEventListener {
    private static final Component KICK_REASON =
            Component.translatable("message.aem.disconnect.required");

    @Override
    public void onDisconnected(ArchipelagoClient client) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }

        // Cached data is what makes playing on safe. Without it the locks have nothing to read, so
        // this is still the old, protective kick.
        if (!APSlotGate.isOffline()) {
            AEM.LOGGER.warn("Archipelago session lost before this world cached its slot data; "
                    + "removing players rather than letting the world generate blind.");
            server.execute(() -> {
                // Copy first: disconnecting mutates the player list.
                for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                    player.connection.disconnect(KICK_REASON);
                }
            });
            return;
        }

        AEM.LOGGER.warn("Archipelago session lost. Continuing offline on this world's cached slot "
                + "data; checks are being queued. Use /aem reconnect to restore the link.");
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                Component.translatable("message.aem.offline.entered"), false));
    }
}
