package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Holds the Bad Omen -> Raid Omen conversion back while any raid mob is still spawn-locked.
 *
 * <p>{@code applyEffectTick} is the single point where Bad Omen becomes a raid: standing in a
 * village with the effect swaps it for Raid Omen and records the raid position, and Raid Omen is
 * what actually starts the siege. Cancelling here therefore cancels the raid at its source, rather
 * than letting one start and then fail.
 *
 * <p>It has to be stopped here because a raid whose roster is incomplete cannot be won. A wave only
 * clears once its raiders are dead, and a locked raider never spawns (its {@code addEntity} is
 * refused by the mob-lock), so the wave sits at a count it can never reach — the raid bar hangs
 * over the village forever and the Bad Omen is spent for nothing.
 *
 * <p>Returning {@code true} is what keeps this recoverable: vanilla returns {@code false} only on
 * the conversion path, which is how the effect signals it has been consumed. Cancelling with
 * {@code true} leaves Bad Omen on the player, ticking and unspent, so once the missing unlocks
 * arrive the very next tick in a village converts it and the raid starts as usual. The player loses
 * nothing but time.
 */
// Targeted by name: BadOmenMobEffect is package-private, so it cannot be imported from here.
@Mixin(targets = "net.minecraft.world.effect.BadOmenMobEffect")
public abstract class BadOmenMobEffectMixin {
    @Inject(method = "applyEffectTick", at = @At("HEAD"), cancellable = true)
    private void aem$holdRaidWhileRaidersLocked(ServerLevel level, LivingEntity entity, int amplifier,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (MobSpawnLockService.isAnyRaidMobLocked()) {
            cir.setReturnValue(true);
        }
    }
}
