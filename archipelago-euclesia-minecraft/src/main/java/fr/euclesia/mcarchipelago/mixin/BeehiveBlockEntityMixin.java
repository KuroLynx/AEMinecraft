package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops a beehive from spamming the bee-exit sound while bees are spawn-locked.
 *
 * <p>A hive releases an occupant by creating the bee, playing {@code BEEHIVE_EXIT}, then adding it to
 * the world. The mob-spawn-lock refuses that add (see {@link ServerLevelMixin}), so
 * {@code releaseOccupant} returns {@code false}; {@code tickOccupants} only drops an occupant when the
 * release succeeds, so it keeps the bee and retries every tick — replaying the sound endlessly. We
 * short-circuit at the top of {@code releaseOccupant}: while {@code minecraft:bee} is locked, refuse
 * the release before the sound plays, so the bee just stays inside until its unlock item arrives.
 * Vanilla beehives only ever hold bees, so gating on the bee id is sufficient.
 */
@Mixin(BeehiveBlockEntity.class)
public abstract class BeehiveBlockEntityMixin {
    @Inject(method = "releaseOccupant", at = @At("HEAD"), cancellable = true)
    private static void archipelago_euclesia$silenceLockedBeeRelease(CallbackInfoReturnable<Boolean> cir) {
        if (MobSpawnLockService.isMobLocked("minecraft:bee")) {
            cir.setReturnValue(false);
        }
    }
}
