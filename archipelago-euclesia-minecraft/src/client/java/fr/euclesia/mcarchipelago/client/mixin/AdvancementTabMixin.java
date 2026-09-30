package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.client.render.AdvancementRenderHooks;
import fr.euclesia.mcarchipelago.client.render.ArchipelagoTabIcon;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flags when the Archipelago tracker tab's button icon is being extracted, so
 * {@code AdvancementTabTypeMixin} can swap in the logo at the vanilla-computed icon position.
 */
@Mixin(AdvancementTab.class)
public abstract class AdvancementTabMixin {
    // 26.3 hands the root out as its holder rather than its node.
    //? if >=26.3 {
    /*@Shadow
    public abstract net.minecraft.advancements.AdvancementHolder getRootAdvancement();
    *///?} else {
    @Shadow
    public abstract AdvancementNode getRootNode();
    //?}

    @Inject(method = "extractIcon(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("HEAD"))
    private void archipelago_euclesia$markIconStart(GuiGraphicsExtractor graphics, int x, int y, CallbackInfo ci) {
        //? if >=26.3 {
        /*net.minecraft.advancements.AdvancementHolder root = getRootAdvancement();
        ArchipelagoTabIcon.rendering = AdvancementRenderHooks.isTabRoot(root == null ? null : root.id());
        *///?} else {
        AdvancementNode root = getRootNode();
        ArchipelagoTabIcon.rendering =
                AdvancementRenderHooks.isTabRoot(root == null ? null : root.holder().id());
        //?}
    }

    @Inject(method = "extractIcon(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("RETURN"))
    private void archipelago_euclesia$markIconEnd(GuiGraphicsExtractor graphics, int x, int y, CallbackInfo ci) {
        ArchipelagoTabIcon.rendering = false;
    }
}
