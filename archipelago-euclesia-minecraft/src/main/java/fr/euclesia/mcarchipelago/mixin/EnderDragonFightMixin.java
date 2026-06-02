package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pauses the Ender Dragon fight while the dragon is spawn-locked by Archipelago. Cancelling
 * {@code tick} keeps the fight in its pristine "just entered the End" state — the dragon is never
 * created (so {@code dragonUUID} stays unset) and the boss bar is never populated. The moment the
 * unlock item arrives, {@code tick} resumes and the fight spawns the dragon on its next tick, even if
 * the player is already standing in the End — no dimension re-entry required.
 *
 * <p>Without this, the fight would create the dragon, have its {@code addEntity} refused by the
 * mob-lock, yet still record a {@code dragonUUID} and show an empty boss bar — leaving the End
 * permanently dragonless until reload. See [[mob-spawn-lock-feature]].
 */
@Mixin(EnderDragonFight.class)
public abstract class EnderDragonFightMixin {
    private static final String ENDER_DRAGON_ID = "minecraft:ender_dragon";

    @Shadow
    private ServerBossEvent dragonEvent;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$pauseWhileDragonLocked(CallbackInfo ci) {
        if (MobSpawnLockService.isMobLocked(ENDER_DRAGON_ID)) {
            if (dragonEvent != null && dragonEvent.isVisible()) {
                dragonEvent.setVisible(false);
            }
            ci.cancel();
        }
    }
}
