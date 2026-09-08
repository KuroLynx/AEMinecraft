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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Advancements Reloaded ({@code codes.atomys.advr}) equivalent of the vanilla
 * {@code AdvancementWidgetMixin} — same {@link AdvancementRenderHooks}/{@link HintHoldTracker}
 * calls, different call sites, since that mod fully reimplements tile rendering instead of using
 * vanilla's {@code AdvancementWidget}. Only active while Advancements Reloaded is loaded (see
 * {@link AdvReloadedMixinPlugin}).
 *
 * <p>Verified against Advancements Reloaded {@code versions/26.x}
 * ({@code AdvancementReloadedWidget.renderWidgets}/{@code isMouseOn}). This config's
 * {@code defaultRequire} is 0, so if a future release of that mod renames these members, these
 * injectors simply stop applying rather than crashing the game.
 */
@Mixin(targets = "codes.atomys.advr.screens.AdvancementReloadedWidget")
public abstract class AdvReloadedWidgetMixin {
    @Shadow
    private AdvancementNode advancement;

    @Shadow
    private int x;

    @Shadow
    private int y;

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
        Integer rgb = id == null ? null : AdvancementRenderHooks.frameColor(id);
        if (rgb == null) {
            graphics.blitSprite(pipeline, sprite, x, y, width, height);
        } else {
            graphics.blitSprite(pipeline, sprite, x, y, width, height, 0xFF000000 | rgb);
        }
    }

    @Inject(method = "isMouseOn(IIDD)Z", at = @At("RETURN"))
    private void archipelago_euclesia$reportHover(int originX, int originY, double mouseX, double mouseY,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (advancement != null && cir.getReturnValueZ()) {
            HintHoldTracker.reportHover(advancement.holder().id(), originX + x, originY + y, true);
        }
    }
}
