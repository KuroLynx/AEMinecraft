package fr.euclesia.mcarchipelago.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Draws the Archipelago logo as the tracker tab's button icon.
 *
 * <p>The tab icon position is computed inside {@code AdvancementTabType.extractIcon} (it varies
 * by tab edge/index), so rather than re-deriving it we let vanilla compute the spot and only swap
 * what is drawn there: {@code AdvancementTabMixin} sets {@link #rendering} while the Archipelago
 * tab's icon is being extracted, and {@code AdvancementTabTypeMixin} redirects the {@code fakeItem}
 * call to {@link #draw} when the flag is set. Render thread only, so a plain static flag is fine.
 */
public final class ArchipelagoTabIcon {
    public static final Identifier LOGO =
            Identifier.fromNamespaceAndPath("aem", "textures/gui/tracker/archipelago_logo.png");

    private static final int SIZE = 16;

    /** True only while the Archipelago tab's icon is being extracted. */
    public static boolean rendering;

    private ArchipelagoTabIcon() {}

    public static void draw(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
    }
}
