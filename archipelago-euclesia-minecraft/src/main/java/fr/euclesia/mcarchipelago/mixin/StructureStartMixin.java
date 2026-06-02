package fr.euclesia.mcarchipelago.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCapture;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureService;
import fr.euclesia.mcarchipelago.server.gameplay.StructureLockService;
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
 * Enforces the Archipelago structure-lock. A locked structure still runs its full {@code placeInChunk}
 * (so heightmap/terrain context is the genuine worldgen-time state), but {@link WorldGenRegionMixin}
 * diverts every write into a {@link StructureCapture} session rather than the world. The captured
 * placement is persisted and applied verbatim once the structure's unlock item arrives.
 *
 * <p>Wrapping the call in a try/finally guarantees the per-thread session is always cleared, even if a
 * structure piece throws — otherwise a leaked session would silently capture (and discard) the rest of
 * that worldgen thread's terrain.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Shadow
    public abstract Structure getStructure();

    @WrapMethod(method = "placeInChunk")
    private void archipelago_euclesia$captureLockedPlacement(WorldGenLevel level, StructureManager structureManager,
                                                             ChunkGenerator generator, RandomSource random,
                                                             BoundingBox chunkBB, ChunkPos chunkPos,
                                                             Operation<Void> original) {
        String lockedId = StructureLockService.lockedStructureId(level, getStructure());
        if (lockedId == null) {
            original.call(level, structureManager, generator, random, chunkBB, chunkPos);
            return;
        }
        StructureCapture.begin(lockedId);
        try {
            original.call(level, structureManager, generator, random, chunkBB, chunkPos);
        } finally {
            StructureCaptureService.finish(level.getLevel());
        }
    }
}
