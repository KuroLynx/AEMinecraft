package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Right-clicking armor (or an elytra, a carved pumpkin…) puts it straight on: {@code Equippable}
 * swaps it into the equipment slot without going through {@code ArmorSlot}, so
 * {@link InventoryLockArmorSlotMixin} never sees it. Refuse while that slot is locked.
 */
@Mixin(Equippable.class)
public abstract class InventoryLockEquipMixin {
    @Inject(method = "swapWithEquipmentSlot", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$blockLockedEquip(ItemStack stack, Player player,
                                                       CallbackInfoReturnable<InteractionResult> cir) {
        int index = InventoryLockService.indexOf(((Equippable) (Object) this).slot());
        if (index >= 0 && InventoryLockService.isLocked(index)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
