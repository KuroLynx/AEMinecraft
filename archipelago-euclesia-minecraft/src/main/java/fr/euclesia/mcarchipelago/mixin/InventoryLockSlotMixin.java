package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces {@code inventory_lock} ({@link InventoryLockService}): refuses to place an item into a
 * locked slot of the player's own inventory. Both {@link Slot} (hotbar/main) and {@link ArmorSlot}
 * (armor + offhand, which reuses {@code ArmorSlot} for {@code EquipmentSlot.OFFHAND}) are targeted,
 * since {@code ArmorSlot} overrides {@code mayPlace} rather than delegating to the base class — a
 * mixin on {@link Slot} alone would never see an armor/offhand placement.
 *
 * <p>A different concern from {@code SlotMixin}'s material/tool lock, which explicitly leaves the
 * player's own inventory alone (see its doc comment) — that gates specific ITEMS from external
 * sources, this gates specific SLOTS regardless of item. Only placement is blocked: an item already
 * sitting in a slot before it was ever locked is left alone, and the lock only ever grows the
 * unlocked set, never shrinks it, so nothing already placed can become newly invalid.
 *
 * <p>{@code ArmorSlot} is targeted by name (string form): it is package-private, so it cannot be
 * referenced as {@code ArmorSlot.class}.
 */
@Mixin(value = Slot.class, targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class InventoryLockSlotMixin {
    @Shadow
    @Final
    public Container container;

    @Shadow
    public abstract int getContainerSlot();

    @Inject(method = "mayPlace", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$blockLockedSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(this.container instanceof Inventory)) {
            return;
        }
        if (InventoryLockService.isLocked(getContainerSlot())) {
            cir.setReturnValue(false);
        }
    }
}
