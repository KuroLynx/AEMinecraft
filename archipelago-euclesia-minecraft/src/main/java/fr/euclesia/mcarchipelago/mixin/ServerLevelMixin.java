package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces the Archipelago mob-spawn-lock: rejects any entity whose mob is locked at the single
 * {@code addEntity} chokepoint every fresh spawn passes through. Returning {@code false} here is
 * exactly how vanilla reports a refused add, so callers handle it gracefully.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$blockLockedSpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (MobSpawnLockService.shouldBlockSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }
}
