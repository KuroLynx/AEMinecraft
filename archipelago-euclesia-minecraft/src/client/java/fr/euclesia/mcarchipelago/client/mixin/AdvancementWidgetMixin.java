package fr.euclesia.mcarchipelago.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import fr.euclesia.mcarchipelago.client.hint.HintHoldTracker;
import fr.euclesia.mcarchipelago.client.render.AdvancementRenderHooks;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.client.gui.screens.advancements.AdvancementWidgetType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Recolours each advancement tile by its Archipelago logic state: green = in logic,
 * red = out of logic, gray = completed, yellow = glitchable.
 *
 * <p>{@code extractRenderState} draws the tile's frame with a single {@code blitSprite} call. We
 * redirect that call and hand it to {@link AdvancementRenderHooks#drawFrame}, which repaints the
 * sprite in the state colour — its exact shape, bevel and shading, in another hue. The item icon is
 * drawn afterwards and is unaffected. For
 * {@link fr.euclesia.mcarchipelago.client.logic.LogicState#UNKNOWN} the frame is drawn as it comes.
 */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementWidgetMixin {
    // The box drawn behind a hovered tile, in the coordinates it was drawn in, collected across the
    // two nine-slice halves of one extractHover call.
    @Unique
    private int archipelago_euclesia$boxLeft;
    @Unique
    private int archipelago_euclesia$boxTop;
    @Unique
    private int archipelago_euclesia$boxRight;
    @Unique
    private int archipelago_euclesia$boxBottom;
    @Shadow
    private AdvancementNode advancementNode;

    // The tile's offset within the tab; its screen position is the scroll origin plus this.
    @Shadow
    @Final
    private int x;
    @Shadow
    @Final
    private int y;

    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void archipelago_euclesia$colorFrame(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                                 Identifier sprite, int x, int y, int width, int height) {
        AdvancementRenderHooks.drawFrame(graphics, pipeline, sprite, x, y, width, height, frameColor(),
                AdvancementRenderHooks.NO_TINT);
    }

    /**
     * Draw a live mob as the icon for tracker-tab tiles (kill/boss/mob-unlock). The vanilla code
     * draws the JSON item icon via {@code fakeItem}; we redirect it and render the actual entity
     * instead, falling back to the item icon for structures, the tab root, and anything that
     * can't resolve to a living entity.
     */
    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;II)V"))
    private void archipelago_euclesia$trackerIcon(GuiGraphicsExtractor graphics, ItemStack icon, int x, int y) {
        if (advancementNode != null
                && AdvancementRenderHooks.tryDrawTileIcon(graphics, advancementNode.holder().id(), x, y)) {
            return;
        }
        graphics.fakeItem(icon, x, y);
    }

    /** Reports this tile's hover state to {@link HintHoldTracker} every frame it is tested. */
    @Inject(method = "isMouseOver(IIII)Z", at = @At("RETURN"))
    private void archipelago_euclesia$reportHover(int originX, int originY, int mouseX, int mouseY,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (advancementNode != null && cir.getReturnValueZ()) {
            HintHoldTracker.reportHover(advancementNode.holder().id());
        }
    }

    /**
     * Repaints the hovered tile's box in purple as a hint hold completes, for a tile whose progress
     * is split across two box styles: vanilla then draws the box as two nine-slice halves, which are
     * merged here into one rectangle. A tile that is wholly obtained or wholly not takes the single
     * blit in {@link #archipelago_euclesia$hoverBox} instead — the common case by far.
     *
     * <p>Either way the box is caught as it is drawn rather than recomputed: {@code extractHover}
     * runs only for the tile under the cursor, and the blit gives the exact rectangle. The indicator
     * goes on straight after, so the frame and title still draw on top of it.
     */
    @Redirect(
            method = "extractHover(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIFII)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
    private void archipelago_euclesia$box(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                          int textureWidth, int textureHeight, int u, int v,
                                          int boxX, int boxY, int boxWidth, int boxHeight) {
        graphics.blitSprite(pipeline, sprite, textureWidth, textureHeight, u, v, boxX, boxY, boxWidth, boxHeight);
        if (advancementNode == null) {
            return;
        }
        if (archipelago_euclesia$boxRight == 0) {
            archipelago_euclesia$boxLeft = boxX;
            archipelago_euclesia$boxTop = boxY;
            archipelago_euclesia$boxRight = boxX + boxWidth;
            archipelago_euclesia$boxBottom = boxY + boxHeight;
        } else {
            archipelago_euclesia$boxLeft = Math.min(archipelago_euclesia$boxLeft, boxX);
            archipelago_euclesia$boxTop = Math.min(archipelago_euclesia$boxTop, boxY);
            archipelago_euclesia$boxRight = Math.max(archipelago_euclesia$boxRight, boxX + boxWidth);
            archipelago_euclesia$boxBottom = Math.max(archipelago_euclesia$boxBottom, boxY + boxHeight);
        }
        HintHoldTracker.drawHoldBox(graphics, advancementNode.holder().id(),
                archipelago_euclesia$boxLeft, archipelago_euclesia$boxTop,
                archipelago_euclesia$boxRight - archipelago_euclesia$boxLeft,
                archipelago_euclesia$boxBottom - archipelago_euclesia$boxTop);
    }

    /**
     * The whole box in one blit — how vanilla draws it whenever a tile's progress is not split. The
     * same call draws the title box and the tile frame, so the sprite is checked against the box art
     * both widget types use rather than assuming which blit is which.
     */
    @Redirect(
            method = "extractHover(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIFII)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void archipelago_euclesia$hoverBox(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                               Identifier sprite, int boxX, int boxY, int boxWidth, int boxHeight) {
        graphics.blitSprite(pipeline, sprite, boxX, boxY, boxWidth, boxHeight);
        if (advancementNode == null || !archipelago_euclesia$isBoxSprite(sprite)) {
            return;
        }
        HintHoldTracker.drawHoldBox(graphics, advancementNode.holder().id(), boxX, boxY, boxWidth, boxHeight);
    }

    private static boolean archipelago_euclesia$isBoxSprite(Identifier sprite) {
        for (AdvancementWidgetType type : AdvancementWidgetType.values()) {
            if (type.boxSprite().equals(sprite)) {
                return true;
            }
        }
        return false;
    }

    /** Starts each frame's collection clean, so a box that moves never leaves a stale edge behind. */
    @Inject(method = "extractHover(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIFII)V", at = @At("HEAD"))
    private void archipelago_euclesia$resetBox(GuiGraphicsExtractor graphics, int originX, int originY,
                                               float alpha, int tabWidth, int tabHeight, CallbackInfo ci) {
        archipelago_euclesia$boxRight = 0;
    }

    /**
     * Force hidden advancements to behave like visible ones. Both {@code extractRenderState}
     * (drawing the frame + icon) and {@code isMouseOver} (hover/tooltip hit-testing) bail out
     * when {@code display.isHidden()} and the advancement is not done; making that check return
     * false means hidden advancements are drawn AND hoverable (the server already sends them
     * via {@code AdvancementVisibilityEvaluatorMixin}).
     */
    @Redirect(
            method = {
                    "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
                    "isMouseOver(IIII)Z"
            },
            at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/DisplayInfo;isHidden()Z"))
    private boolean archipelago_euclesia$alwaysShowHidden(DisplayInfo display) {
        return !AdvancementRenderHooks.forceVisible();
    }

    /** @return the 0xRRGGBB colour for this advancement, or {@code null} to keep the original frame. */
    private Integer frameColor() {
        return advancementNode == null ? null : AdvancementRenderHooks.frameColor(advancementNode.holder().id());
    }
}
