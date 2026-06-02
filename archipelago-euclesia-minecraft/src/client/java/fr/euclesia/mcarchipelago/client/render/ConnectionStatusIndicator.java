package fr.euclesia.mcarchipelago.client.render;

import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Draws the Archipelago connection indicator in the top-left corner: the Archipelago logo (the same
 * one used for the advancement-tab icon) with its centre re-coloured to show the status — grey before
 * any connection, green while connected, red once a connection has dropped. The colour is applied
 * through a white silhouette mask of the logo (same alpha, white RGB) so it follows the logo's exact
 * shape yet renders at full brightness (tinting the logo itself only multiplied its dark centre).
 * Shared by the in-game HUD element ({@link ConnectionStatusHud}) and the screen overlay so it shows
 * in menus too.
 */
public final class ConnectionStatusIndicator {

    private static final Identifier LOGO =
            Identifier.fromNamespaceAndPath("aem", "textures/gui/tracker/archipelago_logo.png");
    // White-RGB / logo-alpha mask of LOGO; tinting it yields the colour in the logo's exact shape.
    private static final Identifier LOGO_MASK =
            Identifier.fromNamespaceAndPath("aem", "textures/gui/status/logo_mask.png");

    private static final int X = 4;
    private static final int Y = 4;
    private static final int SIZE = 16;

    // The status colour covers only the centre of the logo; CENTER_INSET px are left as the original
    // logo on each edge (so a 16px logo gets a 6x6 coloured core). Tune the inset to grow/shrink it.
    private static final int CENTER_INSET = 5;
    private static final int CENTER_SIZE = SIZE - 2 * CENTER_INSET;

    private static final int UNTINTED = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB0B0B0;
    private static final int GREEN = 0xFF40E040;
    private static final int RED = 0xFFE04040;

    private ConnectionStatusIndicator() {}

    public static void draw(GuiGraphicsExtractor graphics) {
        // Full logo in its own colours, then re-colour just the centre via the silhouette mask so the
        // status colour follows the logo's shape at full brightness.
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, X, Y, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE, UNTINTED);
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO_MASK,
                X + CENTER_INSET, Y + CENTER_INSET,
                (float) CENTER_INSET, (float) CENTER_INSET,
                CENTER_SIZE, CENTER_SIZE, SIZE, SIZE, color());
    }

    private static int color() {
        return switch (APConnectController.INSTANCE.indicator()) {
            case GREEN -> GREEN;
            case RED -> RED;
            case GRAY -> GRAY;
        };
    }
}
