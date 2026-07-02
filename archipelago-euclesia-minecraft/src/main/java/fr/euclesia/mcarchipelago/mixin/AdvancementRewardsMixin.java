package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.BacapRewardService;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.commands.CacheableFunction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Wraps the granting of an advancement's rewards so BlazeandCave's item rewards can't drop gated items
 * straight into the player's inventory. For BACAP reward functions only, snapshots the inventory before
 * the reward runs and, on return, drops any newly granted pickup-blocked item back onto the floor.
 *
 * @see BacapRewardService
 */
@Mixin(AdvancementRewards.class)
public abstract class AdvancementRewardsMixin {
    @Shadow
    @Final
    private Optional<CacheableFunction> function;

    @Inject(method = "grant", at = @At("HEAD"))
    private void archipelago_euclesia$snapshotBeforeReward(ServerPlayer player, CallbackInfo ci) {
        if (archipelago_euclesia$isBacapReward()) {
            BacapRewardService.beforeReward(player);
        }
    }

    @Inject(method = "grant", at = @At("RETURN"))
    private void archipelago_euclesia$dropLockedRewardItems(ServerPlayer player, CallbackInfo ci) {
        if (archipelago_euclesia$isBacapReward()) {
            BacapRewardService.afterReward(player);
        }
    }

    @Unique
    private boolean archipelago_euclesia$isBacapReward() {
        return function.isPresent() && BacapRewardService.isBacapReward(function.get().getId());
    }
}
