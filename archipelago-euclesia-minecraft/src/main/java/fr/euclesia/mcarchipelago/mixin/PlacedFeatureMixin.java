package fr.euclesia.mcarchipelago.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCapture;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureService;
import fr.euclesia.mcarchipelago.server.gameplay.StructureLockService;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Extends the Archipelago structure-lock to feature-based "structures" placed during biome decoration
 * (e.g. {@code minecraft:desert_well}), which are {@link PlacedFeature}s and never go through
 * {@code StructureStart#placeInChunk}. When the feature's id is locked, its placement runs in full but
 * its writes are diverted into a {@link StructureCapture} session (see {@code WorldGenRegionMixin}) and
 * applied verbatim once the unlock item arrives — identical handling to real structures.
 */
@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureMixin {

    @WrapMethod(method = "placeWithBiomeCheck")
    private boolean archipelago_euclesia$captureLockedFeature(WorldGenLevel level, ChunkGenerator generator,
                                                              RandomSource random, BlockPos pos,
                                                              Operation<Boolean> original) {
        String lockedId = StructureLockService.lockedFeatureId(level, (PlacedFeature) (Object) this);
        if (lockedId == null) {
            return original.call(level, generator, random, pos);
        }
        StructureCapture.begin(lockedId);
        try {
            return original.call(level, generator, random, pos);
        } finally {
            StructureCaptureService.finish(level.getLevel());
        }
    }
}
