package fr.euclesia.mcarchipelago.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import fr.euclesia.mcarchipelago.client.logic.LogicColors;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.client.render.TrackerIconRenderer;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Recolours each advancement tile by its Archipelago logic state: green = in logic,
 * red = out of logic, gray = completed, yellow = glitchable.
 *
 * <p>{@code extractRenderState} draws the tile's frame with a single
 * {@code blitSprite} call. We redirect that call and redraw the very same sprite
 * with the state colour, so the box's exact shape (rounded corners, bevel, shading)
 * is preserved — only its colour changes. The item icon is drawn afterwards and is
 * unaffected. For {@link fr.euclesia.mcarchipelago.client.logic.LogicState#UNKNOWN}
 * the original (untinted) frame is drawn.
 */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementWidgetMixin {
    @Shadow
    private AdvancementNode advancementNode;

    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void archipelago_euclesia$colorFrame(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                                 Identifier sprite, int x, int y, int width, int height) {
        Integer rgb = frameColor();
        if (rgb == null) {
            graphics.blitSprite(pipeline, sprite, x, y, width, height);
        } else {
            graphics.blitSprite(pipeline, sprite, x, y, width, height, 0xFF000000 | rgb);
        }
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
                && TrackerIconRenderer.tryRenderIcon(graphics, advancementNode.holder().id(), x, y)) {
            return;
        }
        graphics.fakeItem(icon, x, y);
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
        return false;
    }

    /**
     * Normalise the frame base when we apply a state colour. The frame type is chosen
     * from {@code progress.getPercent() >= 1.0} (OBTAINED gold frame vs UNOBTAINED gray
     * frame). A completed advancement's gold frame would override/distort our tint
     * (e.g. yellow over gold looks unchanged), so we force the neutral UNOBTAINED frame
     * whenever a colour applies — completion is conveyed by the gray CHECKED colour
     * instead. Advancements with no colour ({@code UNKNOWN}) keep vanilla behaviour.
     */
    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/AdvancementProgress;getPercent()F"))
    private float archipelago_euclesia$neutralFrameBase(AdvancementProgress progress) {
        return frameColor() == null ? progress.getPercent() : 0.0f;
    }

    /** @return the 0xRRGGBB colour for this advancement, or {@code null} to keep the original frame. */
    private Integer frameColor() {
        if (advancementNode == null) {
            return null;
        }
        return LogicColors.rgb(LogicProviders.stateFor(advancementNode.holder().id()));
    }
}
