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
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.BooleanSupplier;

/**
 * Extends the Archipelago structure-lock to feature-based "structures" placed during biome decoration
 * (e.g. {@code minecraft:desert_well}), which are {@link PlacedFeature}s and never go through
 * {@code StructureStart#placeInChunk}. When the feature's id is locked, its placement runs in full but
 * its writes are diverted into a {@link StructureCapture} session (see {@code WorldGenRegionMixin}) and
 * applied verbatim once the unlock item arrives — identical handling to real structures.
 *
 * <p>The hook is biome decoration's {@code placeWithBiomeCheck}: on {@link PlacedFeature} itself before
 * 26.3, on the {@code FeaturePlacer} that 26.3 moved placement into from then on.
 */
//? if >=26.3 {
/*@Mixin(net.minecraft.world.level.levelgen.placement.FeaturePlacer.class)
public abstract class PlacedFeatureMixin {
    @Shadow @Final private WorldGenLevel level;

    @WrapMethod(method = "placeWithBiomeCheck")
    private boolean archipelago_euclesia$captureLockedFeature(PlacedFeature feature, RandomSource random, BlockPos pos,
                                                              Operation<Boolean> original) {
        return archipelago_euclesia$capture(level, feature, () -> original.call(feature, random, pos));
    }
*///?} else {
@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureMixin {

    @WrapMethod(method = "placeWithBiomeCheck")
    private boolean archipelago_euclesia$captureLockedFeature(WorldGenLevel level, ChunkGenerator generator,
                                                              RandomSource random, BlockPos pos,
                                                              Operation<Boolean> original) {
        return archipelago_euclesia$capture(level, (PlacedFeature) (Object) this,
                () -> original.call(level, generator, random, pos));
    }
//?}

    private static boolean archipelago_euclesia$capture(WorldGenLevel level, PlacedFeature feature, BooleanSupplier place) {
        String lockedId = StructureLockService.lockedFeatureId(level, feature);
        if (lockedId == null) {
            return place.getAsBoolean();
        }
        StructureCapture.begin(lockedId);
        try {
            return place.getAsBoolean();
        } finally {
            StructureCaptureService.finish(level.getLevel());
        }
    }
}
