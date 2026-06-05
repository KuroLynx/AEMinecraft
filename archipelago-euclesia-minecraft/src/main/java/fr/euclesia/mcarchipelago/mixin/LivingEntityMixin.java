package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.TrapMobService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps trap mobs inert: a mob spawned by a trap item ({@link TrapMobService}) drops no death loot, so
 * killing it can't be farmed for resources. Experience is suppressed at spawn and kill credit in
 * {@code ServerPlayerMixin}; this covers the item loot table.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$noTrapMobLoot(ServerLevel level, DamageSource source, CallbackInfo ci) {
        if (TrapMobService.isTrapMob((Entity) (Object) this)) {
            ci.cancel();
        }
    }
}
