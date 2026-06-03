package fr.euclesia.mcarchipelago.client.finder;

import fr.euclesia.mcarchipelago.server.gameplay.FinderTarget;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * Tier 1 of the Structure Finder, drawn as a custom locator bar so each target shows its
 * representative item icon (see {@link StructureIcons}) instead of a plain waypoint dot. Mirrors the
 * vanilla locator bar's geometry and behaviour: a 182×5 strip centred above the hotbar, with each
 * structure placed by its bearing relative to where the player faces and pinned to the bar edge once
 * it passes {@value #VISIBLE_DEGREE_RANGE}° to either side.
 */
public final class StructureFinderBarHud implements HudElement {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("hud/locator_bar_background");
    // Vanilla ContextualBarRenderer / LocatorBarRenderer geometry (verified against 26.1.2).
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    private static final int MARGIN_BOTTOM = 24;
    private static final int VISIBLE_DEGREE_RANGE = 60;
    // Item icons are 16px; scale them down so they sit nicely on the thin bar.
    private static final float ICON_SCALE = 0.625F;     // 16 → 10 px
    private static final float ICON_PX = 16.0F;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        StructureFinderState.Snapshot snapshot = StructureFinderState.get().snapshot(player.getUUID());
        if (snapshot.tier() < 1 || snapshot.targets().isEmpty()) {
            return;
        }

        int barLeft = (graphics.guiWidth() - BAR_WIDTH) / 2;
        int barTop = graphics.guiHeight() - MARGIN_BOTTOM - BAR_HEIGHT;
        int centerX = barLeft + BAR_WIDTH / 2;
        int iconCenterY = barTop + BAR_HEIGHT / 2;
        int halfSpan = BAR_WIDTH / 2 - 1;

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, barLeft, barTop, BAR_WIDTH, BAR_HEIGHT);

        double yaw = player.getYRot();
        double px = player.getX();
        double pz = player.getZ();
        for (FinderTarget target : snapshot.targets()) {
            double dx = target.pos().getX() + 0.5 - px;
            double dz = target.pos().getZ() + 0.5 - pz;
            double bearing = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
            double relative = Mth.wrapDegrees(bearing - yaw);
            double clamped = Mth.clamp(relative, -VISIBLE_DEGREE_RANGE, VISIBLE_DEGREE_RANGE);
            int x = centerX + (int) Math.round(clamped / VISIBLE_DEGREE_RANGE * halfSpan);
            drawIcon(graphics, StructureIcons.iconFor(target.structureId()), x, iconCenterY);
        }
    }

    private static void drawIcon(GuiGraphicsExtractor graphics, ItemStack stack, int centerX, int centerY) {
        float size = ICON_PX * ICON_SCALE;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX - size / 2.0F, centerY - size / 2.0F);
        pose.scale(ICON_SCALE, ICON_SCALE);
        graphics.item(stack, 0, 0);
        pose.popMatrix();
    }
}
