package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The armor/offhand half of {@link InventoryLockSlotMixin} — {@code ArmorSlot} overrides
 * {@code mayPlace} (and serves {@code EquipmentSlot.OFFHAND} as well as the four armor slots), so a
 * mixin on {@code Slot} alone never sees a placement there.
 *
 * <p>Targeted by name in string form because {@code ArmorSlot} is package-private, and kept in its
 * own class because a single mixin declaring both targets is rejected at load.
 */
@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class InventoryLockArmorSlotMixin {
    @Inject(method = "mayPlace", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$blockLockedArmorSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && InventoryLockService.isLocked((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
