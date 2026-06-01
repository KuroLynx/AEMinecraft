package fr.euclesia.mcarchipelago.client.render;

import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Draws the Archipelago connection sphere in the top-left corner: grey before any connection, green
 * while connected, red once a connection has dropped. Shared by the in-game HUD element
 * ({@link ConnectionStatusHud}) and the screen overlay so it shows in menus too. The texture is a
 * greyscale shaded sphere drawn with a colour tint, so the shading reads through the colour.
 */
public final class ConnectionStatusIndicator {

    private static final Identifier SPHERE =
            Identifier.fromNamespaceAndPath("aem", "textures/gui/status/sphere.png");

    private static final int X = 4;
    private static final int Y = 4;
    private static final int SIZE = 16;

    private static final int GRAY = 0xFFB0B0B0;
    private static final int GREEN = 0xFF40E040;
    private static final int RED = 0xFFE04040;

    private ConnectionStatusIndicator() {}

    public static void draw(GuiGraphicsExtractor graphics) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, SPHERE, X, Y, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE, color());
    }

    private static int color() {
        return switch (APConnectController.INSTANCE.indicator()) {
            case GREEN -> GREEN;
            case RED -> RED;
            case GRAY -> GRAY;
        };
    }
}
