package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.LockFeedback;
import fr.euclesia.mcarchipelago.server.gameplay.MaterialLockService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.item.ItemStack;

/**
 * Enforces the Archipelago material-handling lock: a player can't pick a gated item (diamond, iron,
 * …) off the ground until they've received enough {@code Progressive Material Handling}. Cancelling
 * {@code playerTouch} leaves the item where it is, so it can be collected once the tier is unlocked.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$blockLockedPickup(Player player, CallbackInfo ci) {
        // playerTouch only does anything server-side; the lock check needs the connected session.
        if (player.level().isClientSide()) {
            return;
        }
        Component reason = MaterialLockService.blockReason(getItem());
        if (reason != null) {
            ci.cancel();
            if (player instanceof ServerPlayer serverPlayer) {
                LockFeedback.notify(serverPlayer, reason);
            }
        }
    }
}
