package fr.euclesia.mcarchipelago.client.finder;

import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderTrackerState;
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
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;

/**
 * The Biome Finder's directional indicator: a single icon on the vanilla locator-bar strip
 * pointing toward the last biome {@link fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderService#search}
 * located, replacing the physical compass's lodestone needle now that there is no item to carry
 * one. Same geometry and bearing math as {@link StructureFinderBarHud}, simplified to a single
 * target (no distance-based scaling, no badge) since there is only ever one tracked biome.
 */
public final class BiomeFinderTrackerHud implements HudElement {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("hud/locator_bar_background");
    // Vanilla ContextualBarRenderer / LocatorBarRenderer geometry (verified against 26.1.2).
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    private static final int MARGIN_BOTTOM = 24;
    private static final int VISIBLE_DEGREE_RANGE = 60;
    private static final float ICON_PX = 16.0F;
    // A compass reads as "find your way" without needing a dedicated texture asset.
    private static final ItemStack ICON = new ItemStack(Items.COMPASS);

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        BiomeFinderTrackerState.Target target = BiomeFinderTrackerState.get().target(player.getUUID());
        if (target == null || !target.dimension().equals(player.level().dimension())) {
            return;
        }

        int barLeft = (graphics.guiWidth() - BAR_WIDTH) / 2;
        int barTop = graphics.guiHeight() - MARGIN_BOTTOM - BAR_HEIGHT;
        int centerX = barLeft + BAR_WIDTH / 2;
        int iconCenterY = barTop + BAR_HEIGHT / 2;
        int halfSpan = BAR_WIDTH / 2 - 1;

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, barLeft, barTop, BAR_WIDTH, BAR_HEIGHT);

        BlockPos pos = target.pos();
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double bearing = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double relative = Mth.wrapDegrees(bearing - player.getYRot());
        double clamped = Mth.clamp(relative, -VISIBLE_DEGREE_RANGE, VISIBLE_DEGREE_RANGE);
        int x = centerX + (int) Math.round(clamped / VISIBLE_DEGREE_RANGE * halfSpan);

        drawIcon(graphics, x, iconCenterY);
    }

    private static void drawIcon(GuiGraphicsExtractor graphics, int centerX, int centerY) {
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX - ICON_PX / 2.0F, centerY - ICON_PX / 2.0F);
        graphics.item(ICON, 0, 0);
        pose.popMatrix();
    }
}
