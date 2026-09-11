package fr.euclesia.mcarchipelago.client.mixin;

import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes where the recipe-book button sits, so the Biome Finder button can be placed beside it
 * (see {@code AEMScreenButtons}). Vanilla computes that position from {@code leftPos} — which is
 * protected, like the method — and asking the screen itself keeps the two buttons together even if
 * the inventory layout moves.
 */
@Mixin(InventoryScreen.class)
public interface InventoryScreenAccessor {
    @Invoker("getRecipeBookButtonPosition")
    ScreenPosition archipelago_euclesia$recipeBookButtonPosition();
}
