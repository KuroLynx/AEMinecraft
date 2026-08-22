package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Set;

/**
 * Hands back everything that was locked only because we did not yet know the slot.
 *
 * <p>The counterpart to {@link fr.euclesia.mcarchipelago.server.runtime.APSlotGate}. While a slot is
 * expected but unknown the world generates fail-closed — every structure captured rather than placed,
 * every worldgen mob deferred — because a structure placed for real can never be un-placed, while a
 * captured one can always be placed later. That trade only works if somebody actually places them,
 * and this is that somebody: the moment the slot data lands, anything it does not lock is applied
 * into the live world exactly as if its unlock item had just arrived.
 *
 * <p>Reuses the unlock path rather than a parallel one, so a structure released here is placed by the
 * same code that has always placed unlocked structures — no second way for it to go wrong.
 *
 * <p>Idempotent: it releases what is captured but not locked, so running it twice is a no-op, and
 * running it after a reconnect simply finds nothing to do.
 */
public final class SlotReleaseService {
    private SlotReleaseService() {}

    /**
     * Called once the slot's data is known. Everything captured during the blind window that this
     * slot does not actually lock is placed now.
     */
    public static void releaseUnlockedContent() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }
        server.execute(() -> {
            releaseStructures(server);
            releaseMobs(server);
        });
    }

    private static void releaseStructures(MinecraftServer server) {
        Set<String> release = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (String structureId : StructureCaptureService.capturedStructureIds(level)) {
                if (!AEM.ARCHIPELAGO.client().registries().apStructures().isLocked(structureId)) {
                    release.add(structureId);
                }
            }
        }
        if (release.isEmpty()) {
            return;
        }
        AEM.LOGGER.info("Slot data known: placing {} structure type(s) captured before the handshake.",
                release.size());
        AEMDebug.log("slotRelease structures {}", release);
        StructureCaptureService.applyUnlocked(server, release);
    }

    private static void releaseMobs(MinecraftServer server) {
        Set<String> release = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (String mobId : StructureCaptureService.pendingMobIds(level)) {
                if (!AEM.ARCHIPELAGO.client().registries().apMobs().isSpawnLocked(mobId)) {
                    release.add(mobId);
                }
            }
        }
        if (release.isEmpty()) {
            return;
        }
        AEM.LOGGER.info("Slot data known: spawning {} mob type(s) deferred before the handshake.",
                release.size());
        AEMDebug.log("slotRelease mobs {}", release);
        StructureCaptureService.spawnPendingMobs(server, release);
    }
}
