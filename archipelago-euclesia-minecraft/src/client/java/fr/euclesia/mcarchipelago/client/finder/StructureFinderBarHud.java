package fr.euclesia.mcarchipelago.client.finder;

import fr.euclesia.mcarchipelago.server.gameplay.FinderTarget;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * The Structure Finder bar: a custom locator bar showing each revealed structure as its
 * representative item icon (see {@link StructureIcons}), with a small biome badge for variants that
 * share an icon. Mirrors the vanilla locator bar's geometry — a 182×5 strip centred above the hotbar
 * — placing each structure by its bearing relative to where the player faces, pinned to the bar edge
 * once it passes {@value #VISIBLE_DEGREE_RANGE}° to either side. How many structures appear is
 * decided server-side by the finder tier; each icon is scaled by its distance, so nearby structures
 * look big and far ones small.
 */
public final class StructureFinderBarHud implements HudElement {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("hud/locator_bar_background");
    // Vanilla ContextualBarRenderer / LocatorBarRenderer geometry (verified against 26.1.2).
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    private static final int MARGIN_BOTTOM = 24;
    private static final int VISIBLE_DEGREE_RANGE = 60;

    private static final float ICON_PX = 16.0F;
    // Icon scale by distance: NEAR (and closer) renders at MAX_SCALE, FAR (and beyond) at MIN_SCALE.
    private static final float MAX_SCALE = 1.0F;     // 16 px at the player's feet
    private static final float MIN_SCALE = 0.3F;     // ~5 px once far away
    private static final double NEAR_DISTANCE = 32.0;
    private static final double FAR_DISTANCE = 1024.0;
    // Biome badge drawn at this fraction of the main icon, in the bottom-right corner.
    private static final float BADGE_SCALE = 0.55F;
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        StructureFinderState.Snapshot snapshot = StructureFinderState.get().snapshot(player.getUUID());
        List<FinderTarget> targets = snapshot.targets();
        if (snapshot.tier() < 1 || targets.isEmpty()) {
            return;
        }

        int barLeft = (graphics.guiWidth() - BAR_WIDTH) / 2;
        int barTop = graphics.guiHeight() - MARGIN_BOTTOM - BAR_HEIGHT;
        int centerX = barLeft + BAR_WIDTH / 2;
        int iconCenterY = barTop + BAR_HEIGHT / 2;
        int halfSpan = BAR_WIDTH / 2 - 1;

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, barLeft, barTop, BAR_WIDTH, BAR_HEIGHT);

        // Targets are sorted nearest-first; draw far→near so closer (larger) icons land on top.
        for (int i = targets.size() - 1; i >= 0; i--) {
            FinderTarget target = targets.get(i);
            float scale = scaleFor(player, target.pos());
            drawIcon(graphics, target.structureId(), barX(player, target.pos(), centerX, halfSpan), iconCenterY, scale);
        }
    }

    /** Where {@code pos} sits on the bar: its bearing from where the player faces, pinned at the edges. */
    private static int barX(LocalPlayer player, BlockPos pos, int centerX, int halfSpan) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double bearing = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double relative = Mth.wrapDegrees(bearing - player.getYRot());
        double clamped = Mth.clamp(relative, -VISIBLE_DEGREE_RANGE, VISIBLE_DEGREE_RANGE);
        return centerX + (int) Math.round(clamped / VISIBLE_DEGREE_RANGE * halfSpan);
    }

    private static float scaleFor(LocalPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return scaleForDistance(Math.sqrt(dx * dx + dz * dz));
    }

    private static float scaleForDistance(double distance) {
        double t = Mth.clamp((distance - NEAR_DISTANCE) / (FAR_DISTANCE - NEAR_DISTANCE), 0.0, 1.0);
        return (float) Mth.lerp(t, MAX_SCALE, MIN_SCALE);
    }

    private static void drawIcon(GuiGraphicsExtractor graphics, String structureId, int centerX, int centerY,
                                 float scale) {
        float size = ICON_PX * scale;
        float left = centerX - size / 2.0F;
        float top = centerY - size / 2.0F;
        drawItem(graphics, StructureIcons.iconFor(structureId), left, top, scale);

        ItemStack badge = StructureIcons.badgeFor(structureId);
        if (!badge.isEmpty()) {
            float badgeScale = scale * BADGE_SCALE;
            float badgeSize = ICON_PX * badgeScale;
            drawItem(graphics, badge, left + size - badgeSize, top + size - badgeSize, badgeScale);
        }
    }

    private static void drawItem(GuiGraphicsExtractor graphics, ItemStack stack, float left, float top,
                                 float scale) {
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(left, top);
        pose.scale(scale, scale);
        graphics.item(stack, 0, 0);
        pose.popMatrix();
    }
}
