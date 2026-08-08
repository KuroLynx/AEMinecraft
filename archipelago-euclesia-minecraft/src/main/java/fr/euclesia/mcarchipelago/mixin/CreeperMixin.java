package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.TrapExplosions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same treatment as {@link PrimedTntMixin} for the {@code aww_man} trap's creeper: it still goes off in
 * the victim's face, it just doesn't take the wall (or the dropped items) with it. Natural creepers are
 * untouched — only one conjured by the trap carries the tag.
 *
 * <p>Unlike the TNT, the vanilla method is what discards the creeper, so cancelling it means doing that
 * here; otherwise the swell completes again on the very next tick and it detonates forever.
 *
 * <p>The two other things the vanilla method does before discarding — {@code spawnLingeringCloud} and
 * {@code triggerOnDeathMobEffects} — are deliberately not reproduced: both are no-ops for a creeper
 * with no effects, which is every creeper {@code TrapEffects.awwMan} conjures. Give the trap creeper an
 * effect (or charge it) and that behaviour would need adding here.
 */
@Mixin(Creeper.class)
public abstract class CreeperMixin {
    @Inject(method = "explodeCreeper", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$harmlessTrapBlast(CallbackInfo ci) {
        Creeper creeper = (Creeper) (Object) this;
        if (!TrapExplosions.isHarmless(creeper) || !(creeper.level() instanceof ServerLevel level)) {
            return;
        }
        TrapExplosions.detonate(level, creeper, TrapExplosions.CREEPER_RADIUS);
        creeper.discard();
        ci.cancel();
    }
}
