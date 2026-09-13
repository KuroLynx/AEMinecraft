package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.TrapMobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Denies kill credit for trap mobs ({@link TrapMobService}) in {@code awardKillScore}, so killing
 * a trap-conjured creeper or crowd awards no score or kill stat. The matching advancement suppression
 * (e.g. Monster Hunter / the Adventure root) lives in {@code KilledTriggerMixin}.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "awardKillScore", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$noTrapMobKillCredit(Entity killed, DamageSource source, CallbackInfo ci) {
        if (TrapMobService.isTrapMob(killed)) {
            ci.cancel();
        }
    }
}
