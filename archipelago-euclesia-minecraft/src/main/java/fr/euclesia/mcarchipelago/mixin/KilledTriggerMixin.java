package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.TrapMobService;
import net.minecraft.advancements.criterion.KilledTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops trap mobs ({@link TrapMobService}) from satisfying kill advancements. {@code KilledTrigger} is
 * the shared chokepoint for both {@code player_killed_entity} (e.g. Monster Hunter / the Adventure root)
 * and {@code entity_killed_player}; in either direction the trap mob is the {@code entity} argument, so
 * cancelling when it is a trap mob keeps the trap from ever granting an advancement.
 */
@Mixin(KilledTrigger.class)
public abstract class KilledTriggerMixin {
    @Inject(method = "trigger", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$ignoreTrapMobs(ServerPlayer player, Entity entity, DamageSource source,
                                                     CallbackInfo ci) {
        if (TrapMobService.isTrapMob(entity)) {
            ci.cancel();
        }
    }
}
