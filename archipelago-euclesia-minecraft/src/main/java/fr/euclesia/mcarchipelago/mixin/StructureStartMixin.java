package fr.euclesia.mcarchipelago.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.euclesia.mcarchipelago.server.gameplay.StructureLockService;
import fr.euclesia.mcarchipelago.server.gameplay.StructureReplayService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Enforces the Archipelago structure-lock: a locked structure is simply not placed. Its start is
 * still created and stored in the chunk as vanilla intends — only the blocks are withheld — so the
 * terrain keeps the adaptation worldgen carved for it, and {@link StructureReplayService} can ask
 * vanilla for the same start again when the unlock item arrives.
 *
 * <p>All that is recorded is the structure id and its origin chunk. This used to run the placement
 * in full and divert every write into a capture session, persisting the lot: correct, but with
 * {@code structure_unlock: All} it meant millions of blocks in a SavedData that was re-encoded on
 * every autosave, which ended in an OutOfMemoryError on a real server. See
 * {@link fr.euclesia.mcarchipelago.server.gameplay.SuppressedStructureData}.
 *
 * <p>``placeInChunk`` is called once per chunk the structure spans; the record is keyed by origin, so
 * the repeats collapse into one entry.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Shadow
    public abstract Structure getStructure();

    @Shadow
    public abstract ChunkPos getChunkPos();

    @WrapMethod(method = "placeInChunk")
    private void archipelago_euclesia$skipLockedPlacement(WorldGenLevel level, StructureManager structureManager,
                                                          ChunkGenerator generator, RandomSource random,
                                                          BoundingBox chunkBB, ChunkPos chunkPos,
                                                          Operation<Void> original) {
        String lockedId = StructureLockService.lockedStructureId(level, getStructure());
        if (lockedId == null) {
            original.call(level, structureManager, generator, random, chunkBB, chunkPos);
            return;
        }
        // Worldgen thread: the store is the server's, so hop. level.getServer() rather than
        // AEMServerRuntime.server(), which is unset while the spawn chunks generate during load.
        ServerLevel serverLevel = level.getLevel();
        ChunkPos origin = getChunkPos();
        serverLevel.getServer().execute(
                () -> StructureReplayService.recordSuppressed(serverLevel, lockedId, origin));
    }
}
