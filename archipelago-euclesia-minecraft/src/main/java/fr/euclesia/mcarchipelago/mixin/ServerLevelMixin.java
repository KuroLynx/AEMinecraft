package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces the Archipelago mob-spawn-lock by rejecting any entity whose mob is locked, at the two
 * {@code ServerLevel} entry points fresh spawns use. Returning {@code false} is exactly how vanilla
 * reports a refused add, so callers handle it gracefully.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    /**
     * The chokepoint every single-entity add funnels through (natural spawns, {@code /summon},
     * breeding, conversions): block the entity from ever entering the world.
     */
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$blockLockedSpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (MobSpawnLockService.shouldBlockSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Spawners ({@code BaseSpawner}, {@code TrialSpawner}) spawn via this method and use its boolean
     * result to decide whether the spawn counted. The vanilla method always returns {@code true} once
     * it delegates to the void {@code addFreshEntityWithPassengers}, so blocking only in
     * {@code addEntity} (above) would make the entity silently vanish while the spawner still counts
     * it — letting a trial spawner finish its wave and drop loot with no mobs ever fought. Refusing
     * here instead reports the failure, so the trial spawner stays active (and retries) until unlock.
     */
    @Inject(method = "tryAddFreshEntityWithPassengers", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$blockLockedSpawnWithPassengers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (MobSpawnLockService.shouldBlockSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }
}
