package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a locked slot's overlay: a dark tint plus a barrier icon, so a slot the
 * {@code inventory_lock} option has withheld reads as unavailable even before the player tries to
 * place anything in it (enforcement itself is {@code InventoryLockSlotMixin}, server-authoritative;
 * this is display only). Reuses the vanilla barrier icon rather than a new texture asset, the same
 * pragmatic choice already made for the Biome Finder's HUD icon (a plain compass).
 *
 * <p>{@code extractSlot} draws every slot regardless of which container menu is open, so the
 * {@code Inventory}-only check matters here too: a locked slot only ever exists in the player's own
 * inventory (see {@code InventoryLockService}), never in a chest or workstation.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryLockRenderMixin {
    private static final int SLOT_SIZE = 16;
    private static final int DARKEN_ARGB = 0x90000000;
    private static final ItemStack LOCK_ICON = new ItemStack(Items.BARRIER);

    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void archipelago_euclesia$drawLock(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                               CallbackInfo ci) {
        if (!(slot.container instanceof Inventory) || !InventoryLockService.isLocked(slot.getContainerSlot())) {
            return;
        }
        graphics.fill(slot.x, slot.y, slot.x + SLOT_SIZE, slot.y + SLOT_SIZE, DARKEN_ARGB);
        graphics.item(LOCK_ICON, slot.x, slot.y);
    }
}
