package fr.euclesia.mcarchipelago.client.compat.betteradvancements;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import fr.euclesia.mcarchipelago.client.hint.HintHoldTracker;
import fr.euclesia.mcarchipelago.client.render.AdvancementRenderHooks;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Better Advancements ({@code betteradvancements}) equivalent of the vanilla
 * {@code AdvancementWidgetMixin} — same {@link AdvancementRenderHooks}/{@link HintHoldTracker}
 * calls, different call sites, since that mod fully reimplements tile rendering. Only active
 * while Better Advancements is loaded (see {@link BetterAdvMixinPlugin}).
 *
 * <p>Unlike vanilla/Advancements Reloaded, Better Advancements' own icon/frame draw calls already
 * carry a per-tile colour argument (its own "customizable icon colours" feature) — we redirect
 * those coloured overloads and only override the colour when we have one, otherwise passing its
 * original colour through unchanged.
 *
 * <p>Verified against way2muchnoise/BetterAdvancements
 * ({@code BetterAdvancementWidget.draw}/{@code isMouseOver}). This config's
 * {@code defaultRequire} is 0, so a future rename of these members makes these injectors simply
 * stop applying rather than crashing the game.
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementWidget")
public abstract class BetterAdvWidgetMixin {
    @Shadow
    public abstract AdvancementNode getAdvancement();

    @Shadow
    public abstract int getX();

    @Shadow
    public abstract int getY();

    @Redirect(
            method = "draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;III)V"))
    private void archipelago_euclesia$icon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, int color) {
        Identifier id = archipelago_euclesia$id();
        if (id == null || !AdvancementRenderHooks.tryDrawTileIcon(graphics, id, x, y)) {
            graphics.fakeItem(stack, x, y, color);
        }
    }

    @Redirect(
            method = "draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
    private void archipelago_euclesia$frame(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                            Identifier sprite, int x, int y, int width, int height, int color) {
        Identifier id = archipelago_euclesia$id();
        Integer rgb = id == null ? null : AdvancementRenderHooks.frameColor(id);
        graphics.blitSprite(pipeline, sprite, x, y, width, height, rgb == null ? color : (0xFF000000 | rgb));
    }

    @Inject(method = "isMouseOver(DDDDF)Z", at = @At("RETURN"))
    private void archipelago_euclesia$reportHover(double scrollX, double scrollY, double mouseX, double mouseY,
                                                  float zoom, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        AdvancementNode node = getAdvancement();
        if (node != null) {
            HintHoldTracker.reportHover(node.holder().id(), (int) scrollX + getX() + 3, (int) scrollY + getY(), true);
        }
    }

    private Identifier archipelago_euclesia$id() {
        AdvancementNode node = getAdvancement();
        return node == null ? null : node.holder().id();
    }
}
