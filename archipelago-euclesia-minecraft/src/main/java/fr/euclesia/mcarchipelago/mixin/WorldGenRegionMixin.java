package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCapture;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCapture.CaptureSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diverts the writes of a locked structure's placement into the active {@link CaptureSession} (set up
 * by {@code StructureStartMixin}) instead of the world. Only intercepts while a session is active on
 * this worldgen thread, so ordinary chunk generation is untouched. Reads (state / block entity) of a
 * captured position return the captured value so the structure piece can populate block entities; all
 * other reads fall through to the real terrain, keeping the worldgen-time context intact.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {

    @Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$captureSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                      CallbackInfoReturnable<Boolean> cir) {
        CaptureSession session = StructureCapture.current();
        if (session != null) {
            session.captureBlock(pos.immutable(), state);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$captureGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        CaptureSession session = StructureCapture.current();
        if (session != null) {
            BlockState captured = session.blockAt(pos);
            if (captured != null) {
                cir.setReturnValue(captured);
            }
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$captureGetBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        CaptureSession session = StructureCapture.current();
        if (session != null) {
            BlockEntity captured = session.blockEntityAt(pos);
            if (captured != null) {
                cir.setReturnValue(captured);
            }
        }
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$captureAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        CaptureSession session = StructureCapture.current();
        if (session != null) {
            session.captureEntity(entity);
            cir.setReturnValue(true);
            return;
        }
        // Worldgen-time spawns (chunk-generation animals such as rabbits/camels, and structure mobs of
        // an already-unlocked structure) bypass ServerLevel#addEntity, so the mob-spawn-lock must be
        // enforced here too. Returning false is vanilla's "add refused" signal.
        if (MobSpawnLockService.shouldBlockSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }
}
