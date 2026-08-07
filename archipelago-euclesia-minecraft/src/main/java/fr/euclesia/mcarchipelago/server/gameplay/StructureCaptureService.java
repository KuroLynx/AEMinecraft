package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCapture.CaptureSession;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureData.CapturedBlock;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureData.CapturedPlacement;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.Set;

/**
 * Bridges the structure-lock between worldgen (where a locked structure's writes are captured) and the
 * moment its unlock item arrives (where the captured writes are applied to the live world). Structure
 * mobs whose own type is still locked are held in {@link PendingMobData} and spawned when that mob
 * unlocks. All world mutation here runs on the server thread.
 */
public final class StructureCaptureService {
    private StructureCaptureService() {}

    /**
     * Ends the capture session opened for the just-finished {@code placeInChunk} and persists it.
     * Serialization happens here (on the worldgen thread); the store mutation is hopped to the server
     * thread. Safe to call when no session is active.
     */
    public static void finish(ServerLevel level) {
        CaptureSession session = StructureCapture.end();
        if (session == null || session.isEmpty()) {
            return;
        }
        // level.getServer(), NOT AEMServerRuntime.server(): the latter is only set on SERVER_STARTED,
        // and spawn chunks generate during level load, BEFORE that. Reading it here returned null and
        // dropped straight out — but StructureCapture.end() above has already consumed the session, so
        // the structure's writes were diverted away from the world AND the capture thrown away. The
        // structure was neither placed nor stored: gone, with nothing left to release. A ServerLevel
        // always knows its server, whatever stage of startup we are in.
        MinecraftServer server = level.getServer();
        HolderLookup.Provider provider = level.registryAccess();
        String structureId = session.structureId();
        CapturedPlacement placement = session.toPersistable(provider);
        server.execute(() -> captureData(level).add(structureId, placement));
    }

    /** Structure ids holding captured placements in this level (see SlotReleaseService). */
    public static Set<String> capturedStructureIds(ServerLevel level) {
        return captureData(level).capturedIds();
    }

    /** Mob ids holding deferred worldgen spawns in this level (see SlotReleaseService). */
    public static Set<String> pendingMobIds(ServerLevel level) {
        return pendingMobData(level).pendingIds();
    }

    /**
     * Queues every captured placement of the given structures for application. Server thread.
     *
     * <p>Queued rather than applied on the spot: one unlock is one structure type and would be fine,
     * but a mass unlock — another player finishing their game and releasing — delivers dozens at once,
     * and writing every placement of every type in a single tick stalls the server hard enough that
     * the structures look like they never arrived. See {@link StructurePlacementQueue}.
     */
    public static void applyUnlocked(MinecraftServer server, Set<String> structureIds) {
        if (structureIds.isEmpty()) {
            return;
        }
        int queued = 0;
        for (ServerLevel level : server.getAllLevels()) {
            StructureCaptureData data = captureData(level);
            for (String structureId : structureIds) {
                for (CapturedPlacement placement : data.drain(structureId)) {
                    StructurePlacementQueue.enqueue(level, structureId, placement);
                    queued++;
                }
            }
        }
        if (queued > 0) {
            AEM.LOGGER.info("Unlocked {} structure type(s): {} placement(s) queued.",
                    structureIds.size(), queued);
        }
    }

    /** Applies one queued placement. Called only by {@link StructurePlacementQueue}. */
    static void applyPlacementNow(ServerLevel level, CapturedPlacement placement) {
        applyPlacement(level, placement);
    }

    /** Puts a placement back in storage when the server stops before it could be applied. */
    static void restoreCaptured(ServerLevel level, String structureId, CapturedPlacement placement) {
        captureData(level).add(structureId, placement);
    }

    /**
     * Defers a worldgen-placed mob whose type is spawn-locked so it is not lost. The mob is serialized
     * now (on the worldgen thread, with its generation-time position) and stored in
     * {@link PendingMobData}, to be spawned by {@link #spawnPendingMobs} once the mob's unlock item
     * arrives — exactly like the structure-lock path, but for mobs of structures that generated
     * normally (i.e. were never captured). The store mutation is hopped to the server thread.
     *
     * <p>Only root entities are stored: a root's NBT already serializes its passenger stack, so
     * storing passengers separately would double-spawn them on unlock (mirrors
     * {@code CaptureSession#captureEntity}).
     */
    public static void deferWorldgenMob(ServerLevel level, Entity entity) {
        if (entity.isPassenger()) {
            return;
        }
        TagValueOutput output =
                TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        if (!entity.save(output)) {
            return;
        }
        CompoundTag entityNbt = output.buildResult();
        String mobId = entityNbt.getStringOr("id", "");
        if (mobId.isEmpty()) {
            return;
        }
        // Same reasoning as finish(): the runtime reference is not set this early in startup, and a
        // deferred mob dropped here is a mob that never spawns at all.
        MinecraftServer server = level.getServer();
        server.execute(() -> pendingMobData(level).add(mobId, entityNbt));
    }

    /** Spawns structure mobs that were waiting on the given mob types to unlock. Server thread. */
    public static void spawnPendingMobs(MinecraftServer server, Set<String> mobIds) {
        if (mobIds.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            PendingMobData data = pendingMobData(level);
            for (String mobId : mobIds) {
                for (CompoundTag entityNbt : data.drain(mobId)) {
                    spawnEntity(level, entityNbt);
                }
            }
        }
    }

    private static void applyPlacement(ServerLevel level, CapturedPlacement placement) {
        HolderLookup.Provider provider = level.registryAccess();
        for (CapturedBlock captured : placement.blocks()) {
            // UPDATE_CLIENTS mirrors worldgen placement (send to clients, no neighbor physics).
            level.setBlock(captured.pos(), captured.state(), Block.UPDATE_CLIENTS);
            captured.blockEntity().ifPresent(tag -> loadBlockEntity(level, captured, provider, tag));
        }
        for (CompoundTag entityNbt : placement.entities()) {
            spawnOrDeferMob(level, entityNbt);
        }
    }

    private static void loadBlockEntity(ServerLevel level, CapturedBlock captured,
                                        HolderLookup.Provider provider, CompoundTag tag) {
        BlockEntity blockEntity = level.getBlockEntity(captured.pos());
        if (blockEntity == null) {
            return;
        }
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, provider, tag);
        blockEntity.loadWithComponents(input);
        blockEntity.setChanged();
    }

    private static void spawnOrDeferMob(ServerLevel level, CompoundTag entityNbt) {
        String mobId = entityNbt.getStringOr("id", "");
        if (!mobId.isEmpty() && AEM.ARCHIPELAGO.client().registries().apMobs().isSpawnLocked(mobId)) {
            pendingMobData(level).add(mobId, entityNbt);
            return;
        }
        spawnEntity(level, entityNbt);
    }

    private static void spawnEntity(ServerLevel level, CompoundTag entityNbt) {
        Entity entity = EntityType.loadEntityRecursive(entityNbt, level, EntitySpawnReason.STRUCTURE, EntityProcessor.NOP);
        if (entity != null) {
            level.tryAddFreshEntityWithPassengers(entity);
        }
    }

    private static StructureCaptureData captureData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(StructureCaptureData.TYPE);
    }

    private static PendingMobData pendingMobData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(PendingMobData.TYPE);
    }
}
