package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a locked slot: its face repainted in a disabled shade, plus a lock mark, so a slot the
 * {@code inventory_lock} option has withheld reads as unavailable even before the player tries to
 * place anything in it (enforcement itself is {@code InventoryLockSlotMixin}, server-authoritative;
 * this is display only). The mark is our own 12x12 sprite, centred in the 16x16 slot face, so it
 * can be redrawn without touching this class.
 *
 * <p>{@code extractSlot} draws every slot regardless of which container menu is open, so the test
 * here is the same {@code InventoryLockService.isLocked(Slot)} the placement mixins use — a slot
 * that looks locked and a slot that refuses an item are then always the same slot.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryLockRenderMixin {
    private static final int SLOT_SIZE = 16;
    // Vanilla's slot face is a flat #8B8B8B; this is that face at about a third of its brightness,
    // so a locked slot is drawn as the same slot in a disabled shade instead of having a black
    // sheet laid over it, which dimmed whatever the container's own art had there.
    private static final int LOCKED_FACE_ARGB = 0xFF2F2F2F;
    /** GUI sprite at {@code assets/aem/textures/gui/sprites/locked_slot.png}. */
    private static final Identifier LOCKED_ICON = Identifier.fromNamespaceAndPath("aem", "locked_slot");
    private static final int ICON_SIZE = 12;
    private static final int ICON_INSET = (SLOT_SIZE - ICON_SIZE) / 2;

    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void archipelago_euclesia$drawLock(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                               CallbackInfo ci) {
        if (!InventoryLockService.isLocked(slot)) {
            return;
        }
        graphics.fill(slot.x, slot.y, slot.x + SLOT_SIZE, slot.y + SLOT_SIZE, LOCKED_FACE_ARGB);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, LOCKED_ICON,
                slot.x + ICON_INSET, slot.y + ICON_INSET, ICON_SIZE, ICON_SIZE);
    }
}
