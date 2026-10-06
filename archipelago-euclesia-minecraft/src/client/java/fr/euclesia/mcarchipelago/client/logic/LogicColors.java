package fr.euclesia.mcarchipelago.client.logic;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;

/**
 * Maps a {@link LogicState} to its indicator colour, shared by the icon-background
 * and (stashed) sphere renderers so they always agree.
 *
 * <p>These are the colours as they are actually seen: a tile's frame is repainted in them
 * ({@code AdvancementRenderHooks#drawFrame}), the sprite's flat face becoming exactly this colour
 * and its bevels lighter and darker shades of it. They used to be a tint multiplied over grey
 * artwork, which meant picking values for what the multiply would leave rather than for what should
 * appear — so they can now be re-tuned on how they look, with nothing else to account for.
 */
public final class LogicColors {
    // Minecraft's own text colours, so the tiles speak the palette the rest of the game already uses.
    public static final int RGB_IN_LOGIC = rgb(ChatFormatting.GREEN);
    public static final int RGB_OUT_OF_LOGIC = rgb(ChatFormatting.RED);
    /** Dim, so a completed tile reads as done and stops competing with the ones still to do. */
    public static final int RGB_CHECKED = rgb(ChatFormatting.DARK_GRAY);
    public static final int RGB_GLITCHABLE = rgb(ChatFormatting.YELLOW);
    public static final int RGB_COLLECTED = rgb(ChatFormatting.BLUE);

    private LogicColors() {}

    /** A text colour's RGB. Through TextColor rather than ChatFormatting.getColor(), which 26.2 removed. */
    public static int rgb(ChatFormatting format) {
        return TextColor.fromLegacyFormat(format).getValue();
    }

    /** @return the 0xRRGGBB colour for a state, or {@code null} if no indicator should be drawn. */
    public static Integer rgb(LogicState state) {
        return switch (state) {
            case IN_LOGIC -> RGB_IN_LOGIC;
            case OUT_OF_LOGIC -> RGB_OUT_OF_LOGIC;
            case CHECKED -> RGB_CHECKED;
            case GLITCHABLE -> RGB_GLITCHABLE;
            case COLLECTED -> RGB_COLLECTED;
            case UNKNOWN -> null;
        };
    }
}
