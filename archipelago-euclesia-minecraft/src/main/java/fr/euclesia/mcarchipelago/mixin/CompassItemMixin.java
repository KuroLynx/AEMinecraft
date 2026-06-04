package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.content.BiomeFinderItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the Biome Finder named "Biome Finder" after it starts tracking a biome. The finder is a
 * vanilla compass, and {@link CompassItem#getName} returns the hardcoded "Lodestone Compass" whenever
 * a {@code LODESTONE_TRACKER} is present — which overrides the stack's {@code ITEM_NAME}. We override
 * the name back for finder stacks so the lodestone needle still renders but the name stays ours.
 */
@Mixin(CompassItem.class)
public abstract class CompassItemMixin {
    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$keepFinderName(ItemStack stack, CallbackInfoReturnable<Component> cir) {
        if (BiomeFinderItem.isFinder(stack)) {
            cir.setReturnValue(BiomeFinderItem.displayName());
        }
    }
}
