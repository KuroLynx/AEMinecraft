package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.client.render.ArchipelagoTabIcon;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws the Archipelago logo at the tab's computed icon position (instead of the item icon) when
 * {@link ArchipelagoTabIcon#rendering} is set by {@code AdvancementTabMixin}.
 *
 * <p>{@code AdvancementTabType} is package-private, so it is targeted by name.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.advancements.AdvancementTabType")
public class AdvancementTabTypeMixin {
    @Redirect(
            method = "extractIcon(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIILnet/minecraft/world/item/ItemStack;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;II)V"))
    private void archipelago_euclesia$tabLogo(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        if (ArchipelagoTabIcon.rendering) {
            ArchipelagoTabIcon.draw(graphics, x, y);
        } else {
            graphics.fakeItem(stack, x, y);
        }
    }
}
