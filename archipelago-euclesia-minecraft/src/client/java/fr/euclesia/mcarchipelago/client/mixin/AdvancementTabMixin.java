package fr.euclesia.mcarchipelago.client.mixin;

import fr.euclesia.mcarchipelago.client.render.AdvancementRenderHooks;
import fr.euclesia.mcarchipelago.client.render.ArchipelagoTabIcon;
import fr.euclesia.mcarchipelago.client.render.TrackerGrid;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flags when the Archipelago tracker tab's button icon is being extracted, so
 * {@code AdvancementTabTypeMixin} can swap in the logo at the vanilla-computed icon position; and
 * lays the tracker tabs out as a sectioned grid ({@link TrackerGrid}) instead of a tree.
 */
@Mixin(AdvancementTab.class)
public abstract class AdvancementTabMixin implements fr.euclesia.mcarchipelago.client.render.TrackerGridTab {
    // 26.3 hands the root out as its holder rather than its node.
    //? if >=26.3 {
    /*@Shadow
    public abstract net.minecraft.advancements.AdvancementHolder getRootAdvancement();
    *///?} else {
    @Shadow
    public abstract AdvancementNode getRootNode();
    //?}

    // From 26.2 the tab works out the hovered tile once per game tick (tick() -> isMouseOver), not per frame, so
    // the widget's isMouseOver hook only sees a hover on one frame in several: tile picks for the
    // logic report landed on the third click and hint holds kept resetting. Report the tab's stored
    // hover every frame instead.
    //? if >=26.2 {
    /*@Shadow
    private net.minecraft.client.gui.screens.advancements.AdvancementWidget hovered;

    @Inject(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("HEAD"))
    private void archipelago_euclesia$reportHovered(GuiGraphicsExtractor graphics, int x, int y, CallbackInfo ci) {
        if (hovered != null) {
            fr.euclesia.mcarchipelago.client.hint.HintHoldTracker.reportHover(
                    ((AdvancementWidgetAccessor) hovered).archipelago_euclesia$getAdvancementNode().holder().id());
        }
    }
    *///?}

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

    // ---- tracker tabs as a grid (TrackerGrid) ----

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private AdvancementWidget root;
    @Shadow @Final private Map<AdvancementHolder, AdvancementWidget> widgets;
    @Shadow private double scrollX;
    @Shadow private double scrollY;
    @Shadow private int minX;
    @Shadow private int minY;
    @Shadow private int maxX;
    @Shadow private int maxY;
    @Shadow private boolean centered;

    @Unique
    private List<TrackerGrid.Header> archipelago_euclesia$headers;  // null: the tab keeps its tree

    /** Re-grids after every tile vanilla adds (its tree position and bounds are overwritten). */
    @Inject(method = "addWidget", at = @At("RETURN"))
    private void archipelago_euclesia$grid(CallbackInfo ci) {
        List<TrackerGrid.Header> headers = new ArrayList<>();
        int height = TrackerGrid.layout(root, widgets.values(), headers);
        archipelago_euclesia$headers = height < 0 ? null : headers;
        if (height >= 0) {
            minX = 0;
            minY = 0;
            maxX = TrackerGrid.WIDTH;
            maxY = height;
        }
    }

    /** A grid opens at its top, centred across; vanilla would centre a tall tab on its middle. */
    @Inject(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("HEAD"))
    private void archipelago_euclesia$gridScroll(GuiGraphicsExtractor graphics, int x, int y, CallbackInfo ci) {
        if (archipelago_euclesia$headers != null && !centered) {
            scrollX = (234 - TrackerGrid.WIDTH) / 2.0;  // 234 x 113: vanilla's content area
            scrollY = 0;
            centered = true;
        }
    }

    /** No connector lines on a grid: they would join tiles that are no longer next to each other. */
    @Redirect(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementWidget;extractConnectivity(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZ)V"))
    private void archipelago_euclesia$gridLines(AdvancementWidget widget, GuiGraphicsExtractor graphics,
                                                int x, int y, boolean dropShadow) {
        if (archipelago_euclesia$headers == null) {
            widget.extractConnectivity(graphics, x, y, dropShadow);
        }
    }

    /** Section headers, drawn with the tiles (same scroll, same scissor). */
    @Inject(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementWidget;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"))
    private void archipelago_euclesia$gridHeaders(GuiGraphicsExtractor graphics, int x, int y, CallbackInfo ci) {
        if (archipelago_euclesia$headers == null) {
            return;
        }
        int left = Mth.floor(scrollX) + 2;
        int top = Mth.floor(scrollY);
        for (TrackerGrid.Header header : archipelago_euclesia$headers) {
            graphics.text(minecraft.font, header.label(), left, top + header.y(), 0xFFFFFFFF, true);
        }
    }

    @Override
    public boolean archipelago_euclesia$isGrid() {
        return archipelago_euclesia$headers != null;
    }
}
