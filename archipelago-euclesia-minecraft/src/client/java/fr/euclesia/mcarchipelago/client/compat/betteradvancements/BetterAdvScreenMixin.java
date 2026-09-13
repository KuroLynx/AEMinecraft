package fr.euclesia.mcarchipelago.client.compat.betteradvancements;

import fr.euclesia.mcarchipelago.client.hint.HintHoldTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link HintHoldTracker} once per frame inside the Better Advancements screen — the
 * equivalent of the vanilla {@code AdvancementsScreenMixin}. Only active while Better
 * Advancements is loaded (see {@link BetterAdvMixinPlugin}).
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementsScreen")
public abstract class BetterAdvScreenMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("RETURN"))
    private void archipelago_euclesia$tickHint(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                               float partialTicks, CallbackInfo ci) {
        HintHoldTracker.tick();
    }
}
