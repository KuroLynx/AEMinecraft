package fr.euclesia.mcarchipelago.client.compat.advancementsreloaded;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import fr.euclesia.mcarchipelago.client.hint.HintHoldTracker;
import fr.euclesia.mcarchipelago.client.render.AdvancementRenderHooks;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Advancements Reloaded ({@code codes.atomys.advr}) equivalent of the vanilla
 * {@code AdvancementWidgetMixin} — same {@link AdvancementRenderHooks}/{@link HintHoldTracker}
 * calls, different call sites, since that mod fully reimplements tile rendering instead of using
 * vanilla's {@code AdvancementWidget}. Only active while Advancements Reloaded is loaded (see
 * {@link AdvReloadedMixinPlugin}).
 *
 * <p>Verified against Advancements Reloaded {@code versions/26.x}
 * ({@code AdvancementReloadedWidget.renderWidgets}/{@code drawTooltip}). This config's
 * {@code defaultRequire} is 0, so if a future release of that mod renames these members, these
 * injectors simply stop applying rather than crashing the game.
 */
@Mixin(targets = "codes.atomys.advr.screens.AdvancementReloadedWidget")
public abstract class AdvReloadedWidgetMixin {
    @Shadow
    private AdvancementNode advancement;

    // The box drawn behind this tile, in the coordinates it was drawn in, collected across the two
    // nine-slice halves of one drawTooltip call so the indicator can be laid inside all of it.
    @Unique
    private int archipelago_euclesia$boxLeft;
    @Unique
    private int archipelago_euclesia$boxTop;
    @Unique
    private int archipelago_euclesia$boxRight;
    @Unique
    private int archipelago_euclesia$boxBottom;

    @Redirect(
            method = "renderWidgets(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;II)V"))
    private void archipelago_euclesia$icon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        Identifier id = advancement == null ? null : advancement.holder().id();
        if (id == null || !AdvancementRenderHooks.tryDrawTileIcon(graphics, id, x, y)) {
            graphics.fakeItem(stack, x, y);
        }
    }

    @Redirect(
            method = "renderWidgets(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void archipelago_euclesia$frame(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                            Identifier sprite, int x, int y, int width, int height) {
        Identifier id = advancement == null ? null : advancement.holder().id();
        AdvancementRenderHooks.drawFrame(graphics, pipeline, sprite, x, y, width, height,
                id == null ? null : AdvancementRenderHooks.frameColor(id), AdvancementRenderHooks.NO_TINT);
    }

    /**
     * Reports this tile's hover state, and collects the rectangle to show it on, from the box this
     * mod draws behind a tile — caught as it is drawn rather than recomputed.
     *
     * <p>{@code drawTooltip} runs only for the tile {@code shouldRender} said the cursor is on, so
     * being called at all IS the hover, and the blit gives the exact rectangle: no mirroring of how
     * wide the box is or which side of the icon it flipped to. It is the only user of this
     * nine-argument overload in the method (the description box and the icon frame take the
     * six-argument one), and it draws the box in two nine-slice halves, which are merged here.
     */
    @Redirect(
            method = "drawTooltip(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIFII)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
    private void archipelago_euclesia$box(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                          int textureWidth, int textureHeight, int u, int v,
                                          int x, int y, int width, int height) {
        graphics.blitSprite(pipeline, sprite, textureWidth, textureHeight, u, v, x, y, width, height);
        if (advancement == null) {
            return;
        }
        if (archipelago_euclesia$boxRight == 0) {
            archipelago_euclesia$boxLeft = x;
            archipelago_euclesia$boxTop = y;
            archipelago_euclesia$boxRight = x + width;
            archipelago_euclesia$boxBottom = y + height;
        } else {
            archipelago_euclesia$boxLeft = Math.min(archipelago_euclesia$boxLeft, x);
            archipelago_euclesia$boxTop = Math.min(archipelago_euclesia$boxTop, y);
            archipelago_euclesia$boxRight = Math.max(archipelago_euclesia$boxRight, x + width);
            archipelago_euclesia$boxBottom = Math.max(archipelago_euclesia$boxBottom, y + height);
        }
        HintHoldTracker.reportHover(advancement.holder().id());

        // Straight after the box and over it, so the title and icon still go on top. Redrawn for
        // each half of the nine-slice: the last one covers the whole box, and being opaque, the
        // earlier one it paints over is replaced rather than doubled up.
        HintHoldTracker.drawHoldBox(graphics, advancement.holder().id(),
                archipelago_euclesia$boxLeft, archipelago_euclesia$boxTop,
                archipelago_euclesia$boxRight - archipelago_euclesia$boxLeft,
                archipelago_euclesia$boxBottom - archipelago_euclesia$boxTop);
    }

    /** Starts each frame's collection clean, so a box that moves or changes size never leaves a
     * stale edge behind in the accumulated rectangle. */
    @Inject(method = "drawTooltip(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIFII)V", at = @At("HEAD"))
    private void archipelago_euclesia$resetBox(GuiGraphicsExtractor graphics, int originX, int originY,
                                               float alpha, int tabScreenX, int tabScreenY,
                                               CallbackInfo ci) {
        archipelago_euclesia$boxRight = 0;
    }
}
