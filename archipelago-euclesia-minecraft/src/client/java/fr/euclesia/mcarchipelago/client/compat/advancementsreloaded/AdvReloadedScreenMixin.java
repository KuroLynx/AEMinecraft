package fr.euclesia.mcarchipelago.client.compat.advancementsreloaded;

import fr.euclesia.mcarchipelago.client.hint.HintHoldTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link HintHoldTracker} once per frame inside the Advancements Reloaded screen — the
 * equivalent of the vanilla {@code AdvancementsScreenMixin}. Only active while Advancements
 * Reloaded is loaded (see {@link AdvReloadedMixinPlugin}).
 */
@Mixin(targets = "codes.atomys.advr.screens.AdvancementReloadedScreen")
public abstract class AdvReloadedScreenMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("RETURN"))
    private void archipelago_euclesia$tickHint(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                                               CallbackInfo ci) {
        HintHoldTracker.tick();
    }
}
