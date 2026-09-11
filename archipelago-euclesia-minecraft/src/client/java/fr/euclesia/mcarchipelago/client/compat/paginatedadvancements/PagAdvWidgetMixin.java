package fr.euclesia.mcarchipelago.client.compat.paginatedadvancements;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import fr.euclesia.mcarchipelago.client.mixin.AdvancementWidgetAccessor;
import fr.euclesia.mcarchipelago.client.render.AdvancementRenderHooks;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The Paginated Advancements ({@code paginatedadvancements}) equivalent of the vanilla
 * {@code AdvancementWidgetMixin}. {@code PaginatedAdvancementWidget} subclasses vanilla's
 * {@code AdvancementWidget} but overrides {@code extractRenderState} with its own icon/frame draw
 * calls (twice each — a custom-frame-texture branch and a default-frame branch; one
 * {@code @Redirect} per call shape catches both), so the vanilla mixin's redirects on the parent
 * method body never run for its tiles — this adapter patches the override directly.
 *
 * <p>No hover adapter is needed: this mod does not override {@code isMouseOver}, so the vanilla
 * {@code AdvancementWidgetMixin}'s hover inject (on the inherited method) already fires correctly
 * for these tiles. The advancement id, inherited rather than redeclared, is read via
 * {@link AdvancementWidgetAccessor} since {@code @Shadow} cannot reach a field declared on a
 * superclass from a subclass-targeted mixin.
 *
 * <p>Verified against DaFuqs/PaginatedAdvancements
 * ({@code PaginatedAdvancementWidget.extractRenderState}). This config's {@code defaultRequire}
 * is 0, so a future rename of these members makes these injectors simply stop applying rather
 * than crashing the game. Only active while Paginated Advancements is loaded (see
 * {@link PagAdvMixinPlugin}).
 */
@Mixin(targets = "de.dafuqs.paginatedadvancements.client.PaginatedAdvancementWidget")
public abstract class PagAdvWidgetMixin {
    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;II)V"))
    private void archipelago_euclesia$icon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        Identifier id = archipelago_euclesia$id();
        if (id == null || !AdvancementRenderHooks.tryDrawTileIcon(graphics, id, x, y)) {
            graphics.fakeItem(stack, x, y);
        }
    }

    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void archipelago_euclesia$frame(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
                                            Identifier sprite, int x, int y, int width, int height) {
        Identifier id = archipelago_euclesia$id();
        AdvancementRenderHooks.drawFrame(graphics, pipeline, sprite, x, y, width, height,
                id == null ? null : AdvancementRenderHooks.frameColor(id), AdvancementRenderHooks.NO_TINT);
    }

    private Identifier archipelago_euclesia$id() {
        AdvancementNode node = ((AdvancementWidgetAccessor) (Object) this).archipelago_euclesia$getAdvancementNode();
        return node == null ? null : node.holder().id();
    }
}
