package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The swap keys inside a screen (1-9 over a slot, F for the offhand) move the clicked slot's item
 * into the player's inventory at index {@code button} by writing to the inventory directly: the
 * clicked slot is checked with {@code mayPlace}, the inventory side never is. So a locked hotbar slot
 * or a locked offhand took the item. Refuse the swap when that index is locked and something would
 * move into it.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class InventoryLockSwapKeyMixin {
    @Shadow
    @Final
    public NonNullList<Slot> slots;

    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$blockLockedSwapTarget(int slotId, int button, ContainerInput input,
                                                            Player player, CallbackInfo ci) {
        if (input != ContainerInput.SWAP || slotId < 0 || slotId >= slots.size()) {
            return;
        }
        if (slots.get(slotId).hasItem() && InventoryLockService.isLocked(button)) {
            ci.cancel();
        }
    }
}
