package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The other half of the {@code inventory_lock} enforcement. {@link InventoryLockSlotMixin} gates
 * {@code Slot#mayPlace}, which covers everything the player does through a container screen — but
 * an item that arrives without a screen never touches a {@code Slot} at all: {@code /give}, a BACAP
 * reward, an item picked up off the ground and a crafting result auto-collected all call
 * {@code Inventory#add}, which picks its destination itself. So a locked slot was still the first
 * place such an item landed.
 *
 * <p>Both methods {@code add} chooses with are filtered here rather than cancelling the add: the
 * item still arrives, it just may not occupy a locked slot. If nothing unlocked can take it,
 * {@code add} reports failure and its caller does whatever it already does when the inventory is
 * full — for {@code /give} and a pickup, that means the item drops at the player's feet.
 */
@Mixin(Inventory.class)
public abstract class InventoryLockAddMixin {
    @Shadow
    public abstract ItemStack getItem(int slot);

    @Shadow
    public abstract int getSelectedSlot();

    @Shadow
    private boolean hasRemainingSpaceForItem(ItemStack destination, ItemStack origin) {
        throw new AssertionError("shadow");
    }

    /**
     * Vanilla returns the first empty slot, so every slot before it is occupied: the search for a
     * replacement can start just past it.
     */
    @Inject(method = "getFreeSlot", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$skipLockedFreeSlot(CallbackInfoReturnable<Integer> cir) {
        int slot = cir.getReturnValueI();
        if (slot < 0 || !InventoryLockService.isLocked(slot)) {
            return;
        }
        for (int i = slot + 1; i < Inventory.INVENTORY_SIZE; i++) {
            if (getItem(i).isEmpty() && !InventoryLockService.isLocked(i)) {
                cir.setReturnValue(i);
                return;
            }
        }
        cir.setReturnValue(Inventory.NOT_FOUND_INDEX);
    }

    /**
     * Only reachable when a locked slot already holds a matching stack — items put there before the
     * lock existed, as in a world played offline and connected to Archipelago afterwards. Repeats
     * vanilla's own search order (held slot, offhand, then the rest) minus the locked slots.
     */
    @Inject(method = "getSlotWithRemainingSpace", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$skipLockedPartialSlot(ItemStack stack,
                                                            CallbackInfoReturnable<Integer> cir) {
        int slot = cir.getReturnValueI();
        if (slot < 0 || !InventoryLockService.isLocked(slot)) {
            return;
        }
        for (int candidate : new int[]{getSelectedSlot(), Inventory.SLOT_OFFHAND}) {
            if (!InventoryLockService.isLocked(candidate)
                    && hasRemainingSpaceForItem(getItem(candidate), stack)) {
                cir.setReturnValue(candidate);
                return;
            }
        }
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (!InventoryLockService.isLocked(i) && hasRemainingSpaceForItem(getItem(i), stack)) {
                cir.setReturnValue(i);
                return;
            }
        }
        cir.setReturnValue(Inventory.NOT_FOUND_INDEX);
    }
}
