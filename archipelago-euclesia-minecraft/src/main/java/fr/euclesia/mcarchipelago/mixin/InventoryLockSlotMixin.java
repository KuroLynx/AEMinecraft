package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces {@code inventory_lock} ({@link InventoryLockService}) for the hotbar and main inventory:
 * refuses to place an item into a locked slot. Vanilla routes every placement — a plain click, a
 * shift-click quick-move, and each slot of a click-drag — through {@code mayPlace}, so this one
 * chokepoint covers them all.
 *
 * <p>Armor and the offhand need {@link InventoryLockArmorSlotMixin}: {@code ArmorSlot} overrides
 * {@code mayPlace} rather than delegating here, so this mixin never sees those placements. The two
 * are separate classes on purpose — one mixin declaring both targets is rejected at load
 * ("Found a remappable @Shadow annotation"), which silently disabled the whole lock.
 *
 * <p>A different concern from {@code SlotMixin}'s material/tool lock, which explicitly leaves the
 * player's own inventory alone (see its doc comment) — that gates specific ITEMS from external
 * sources, this gates specific SLOTS regardless of item. Only placement is blocked: an item already
 * sitting in a slot before it was ever locked is left alone, and the lock only ever grows the
 * unlocked set, never shrinks it, so nothing already placed can become newly invalid.
 */
@Mixin(Slot.class)
public abstract class InventoryLockSlotMixin {
    @Inject(method = "mayPlace", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$blockLockedSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        // `this` is typed as the mixin class, so the Object detour is what sees the Slot itself.
        if (cir.getReturnValueZ() && InventoryLockService.isLocked((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
