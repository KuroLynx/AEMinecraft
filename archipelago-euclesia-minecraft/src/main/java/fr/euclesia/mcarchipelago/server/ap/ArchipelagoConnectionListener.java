package fr.euclesia.mcarchipelago.server.ap;

import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Kicks the player out of the world when the Archipelago session drops, mirroring the connect-on-join
 * requirement: a world cannot be played without a live Archipelago link. Does nothing when no server
 * is active — including the expected close fired while leaving a world, since {@code SERVER_STOPPING}
 * clears the server reference before closing the link.
 */
public final class ArchipelagoConnectionListener implements APEventListener {
    private static final Component REASON =
            Component.literal("Disconnected from Archipelago — you must stay connected to play.");

    @Override
    public void onDisconnected(ArchipelagoClient client) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            // Copy first: disconnecting mutates the player list.
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                player.connection.disconnect(REASON);
            }
        });
    }
}
