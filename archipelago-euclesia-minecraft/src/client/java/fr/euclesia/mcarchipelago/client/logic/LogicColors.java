package fr.euclesia.mcarchipelago.client.logic;

/**
 * Maps a {@link LogicState} to its indicator colour, shared by the icon-background
 * and (stashed) sphere renderers so they always agree.
 */
public final class LogicColors {
    public static final int RGB_IN_LOGIC = 0x4CD964;     // green
    public static final int RGB_OUT_OF_LOGIC = 0xE53935; // red
    // Darker than the default frame so completed tiles read as dimmed/grayed-out:
    // a light gray would multiply to ~no change on the already-gray frame sprite.
    public static final int RGB_CHECKED = 0x595959;      // dim gray
    // Bright (and less amber) so it stays clearly yellow after the frame multiply.
    public static final int RGB_GLITCHABLE = 0xFFF64D;   // yellow

    private LogicColors() {}

    /** @return the 0xRRGGBB tint for a state, or {@code null} if no indicator should be drawn. */
    public static Integer rgb(LogicState state) {
        return switch (state) {
            case IN_LOGIC -> RGB_IN_LOGIC;
            case OUT_OF_LOGIC -> RGB_OUT_OF_LOGIC;
            case CHECKED -> RGB_CHECKED;
            case GLITCHABLE -> RGB_GLITCHABLE;
            case UNKNOWN -> null;
        };
    }
}
