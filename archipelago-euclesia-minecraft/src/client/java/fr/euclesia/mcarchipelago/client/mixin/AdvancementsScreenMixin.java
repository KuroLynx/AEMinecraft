package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.client.hint.HintHoldTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives {@link HintHoldTracker} once per frame in the vanilla advancement screen: advances or
 * fires the currently held tile's hint timer, after the screen has finished its own drawing (the
 * indicator itself is drawn by whoever draws the tile, in that tile's own coordinates). The Advancements Reloaded compat adapter has the equivalent
 * inject for its own screen ({@code AdvReloadedScreenMixin}).
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsScreenMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("RETURN"))
    private void archipelago_euclesia$tickHint(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                               float partialTick, CallbackInfo ci) {
        HintHoldTracker.tick();
    }
}
