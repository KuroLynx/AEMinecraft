package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.StructureReplayService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A structure rebuilt into a loaded chunk still marks its fences, rails and walls for post-processing,
 * which a loaded chunk can't hold: vanilla logs a WARN per block and the shapes never update. During a
 * rebuild the marks go to {@link StructureReplayService} instead, which applies the shape update itself.
 * A proto-chunk overrides this method, so worldgen is untouched.
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkPostProcessMixin {
    // 26.2 capitalised the P.
    //? if >=26.2 {
    /*@Inject(method = "markPosForPostProcessing", at = @At("HEAD"), cancellable = true)
    *///?} else {
    @Inject(method = "markPosForPostprocessing", at = @At("HEAD"), cancellable = true)
    //?}
    private void archipelago_euclesia$takeRebuildMark(BlockPos pos, CallbackInfo ci) {
        if (StructureReplayService.takePostProcessing(pos)) {
            ci.cancel();
        }
    }
}
