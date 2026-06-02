package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MaterialLockService;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends the Archipelago material/tool lock past floor pickup: a gated item can't be taken out of a
 * container GUI, crafting result, or furnace output until it's unlocked. Vanilla checks
 * {@code Slot#mayPickup} for both normal clicks and shift-click quick-moves, so this single chokepoint
 * covers every take. Slots backed by the player's own inventory are left alone — once an item is in
 * your inventory it stays yours; we only gate acquiring it from an external source.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow
    @Final
    public Container container;

    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "mayPickup", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$blockLockedTake(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || player.level().isClientSide()) {
            return;
        }
        // Taking/moving items already in the player's own inventory is always allowed.
        if (this.container == player.getInventory()) {
            return;
        }
        if (MaterialLockService.isPickupBlocked(getItem())) {
            cir.setReturnValue(false);
        }
    }
}
