package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.TrapExplosions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends the {@code primed_tnt} trap's blast through {@link TrapExplosions} instead of the vanilla one,
 * so it hurts the victim without cratering their base or deleting the items on the floor.
 *
 * <p>Only TNT the trap itself lit is affected — everyone else's mining charges behave exactly as
 * before. Cancelling here is safe: {@code tick()} discards the entity before it ever calls this, so
 * the TNT still disappears.
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$harmlessTrapBlast(CallbackInfo ci) {
        PrimedTnt tnt = (PrimedTnt) (Object) this;
        if (!TrapExplosions.isHarmless(tnt) || !(tnt.level() instanceof ServerLevel level)) {
            return;
        }
        TrapExplosions.detonate(level, tnt, TrapExplosions.TNT_RADIUS);
        ci.cancel();
    }
}
