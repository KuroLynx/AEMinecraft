package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCapture;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCapture.CaptureSession;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureService;
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
    private void archipelago_euclesia$captureSetBlock(BlockPos pos, BlockState blockState, int updateFlags, int updateLimit,
                                                      CallbackInfoReturnable<Boolean> cir) {
        CaptureSession session = StructureCapture.current();
        if (session != null) {
            session.captureBlock(pos.immutable(), blockState);
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
        // enforced here too. Unlike natural/spawner spawns (which simply resume once unlocked), these
        // are one-time worldgen placements, so we defer rather than discard them: the mob is stored and
        // spawned at its generated position when its unlock item arrives. Returning false is vanilla's
        // "add refused" signal, keeping it out of the world for now.
        //
        // Judged per RIDER STACK, not per entity. A jockey (a Parched on a Camel Husk) is handed to this
        // method one member at a time, so an individual verdict splits the pair: the unlocked member is
        // let through while the locked one is refused, which stranded a rider whose mount never came and
        // — because deferWorldgenMob ignores passengers — dropped a locked rider entirely instead of
        // saving it for its unlock. The root carries the whole stack in its NBT, so deferring the root
        // once preserves mount and rider together.
        Entity root = entity.getRootVehicle();
        if (MobSpawnLockService.shouldBlockStack(root)) {
            if (entity == root) {
                // Only on the root: this method is called again for each passenger, and deferring per
                // member would store (and later spawn) the same stack several times over.
                StructureCaptureService.deferWorldgenMob(((WorldGenRegion) (Object) this).getLevel(), root);
            }
            cir.setReturnValue(false);
        }
    }
}
