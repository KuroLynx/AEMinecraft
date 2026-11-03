package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.client.gui.TrackerSearch;
import fr.euclesia.mcarchipelago.client.hint.HintHoldTracker;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /** Keys go to the tracker search box while it has focus (TrackerSearch#consumesKey). */
    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$searchKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (TrackerSearch.consumesKey(event)) {
            cir.setReturnValue(TrackerSearch.keyPressed(event));
        }
    }
}
