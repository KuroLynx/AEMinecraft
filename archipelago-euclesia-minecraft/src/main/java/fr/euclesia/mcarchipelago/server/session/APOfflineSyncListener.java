package fr.euclesia.mcarchipelago.server.session;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.APEventListener;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.protocol.APReceivedPacket;
import fr.euclesia.mcarchipelago.protocol.APClientStatus;
import fr.euclesia.mcarchipelago.protocol.packet.inbound.ReceivedItemsPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.LocationChecksPacket;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.StatusUpdatePacket;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * Keeps the world's cached session current, and settles up with the room when a link returns.
 *
 * <p>Two halves of the same idea. Going down, every packet that changes what the gates read is
 * mirrored into {@link APSessionCache}, so the file always describes the most recent online moment
 * and an unexpected drop costs nothing. Coming back up, whatever the world did without a link is
 * handed over: the checks in {@link PendingChecks}, and the goal if the run was finished offline.
 *
 * <p>The flush happens on {@code onConnected}, which is after the server has sent this slot's
 * {@code checked_locations} — so the queue is reconciled against what the room already knows rather
 * than assumed to be news. Sending a check the room already has is a no-op there, but skipping them
 * keeps the packet honest and the log readable.
 */
public final class APOfflineSyncListener implements APEventListener {

    @Override
    public void onConnected(ArchipelagoClient client, APReceivedPacket packet) {
        flush(client);
        // Both halves on connect: the slot data may be this world's first, and the resync that
        // follows rewrites progress anyway.
        APSessionCache.saveSlot(AEMServerRuntime.server());
        APSessionCache.saveProgress(AEMServerRuntime.server());
    }

    @Override
    public void onReceivedItems(ArchipelagoClient client, ReceivedItemsPacket packet) {
        APSessionCache.saveProgress(AEMServerRuntime.server());
    }

    @Override
    public void onRoomUpdate(ArchipelagoClient client, APReceivedPacket packet) {
        APSessionCache.saveProgress(AEMServerRuntime.server());
    }

    /**
     * Sends everything the offline run owes the room. Checks the room already recorded are dropped
     * first — they are the overlap between what this world queued and what the server had — so the
     * count reported to the operator is the number of checks the room is actually learning about.
     */
    private static void flush(ArchipelagoClient client) {
        if (!PendingChecks.isLoaded() || PendingChecks.isEmpty()) {
            return;
        }
        boolean goalOwed = PendingChecks.goalPending();
        List<Long> owed = PendingChecks.drain().stream()
                .filter(id -> !client.state().checkedLocations().contains(id))
                .toList();

        if (!owed.isEmpty()) {
            client.send(new LocationChecksPacket(owed));
            AEM.LOGGER.info("Sent {} check(s) earned offline to Archipelago.", owed.size());
            announce(Component.literal("Archipelago: sent " + owed.size()
                    + " check" + (owed.size() == 1 ? "" : "s") + " earned offline."));
        }
        if (goalOwed) {
            client.send(new StatusUpdatePacket(APClientStatus.CLIENT_GOAL));
            PendingChecks.clearGoal();
            AEM.LOGGER.info("Reported the goal reached offline to Archipelago.");
            announce(Component.literal("Archipelago: reported the goal you completed offline."));
        }
    }

    private static void announce(Component message) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(message, false));
    }
}
