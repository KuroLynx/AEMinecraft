package fr.euclesia.mcarchipelago.client.hint;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.net.HintClient;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * Hold-to-hint: holding an item-unlock tile (Knowledge, Structure Unlocks, Entity Unlocks — any
 * tracker of {@link APTrackerRegistry#KIND_UNLOCK kind}) for {@link #HOLD_MILLIS} sends a hint
 * request for that tile's item.
 *
 * <p>Screen-agnostic by design, so it works the same in the vanilla advancement screen and in a
 * compat adapter's replacement screen. Three calls make that up:
 * <ul>
 *   <li>{@link #reportHover} — the tile under the cursor this frame, from
 *       {@code AdvancementWidgetMixin} or an adapter's equivalent.</li>
 *   <li>{@link #tick} — once per frame from that screen's render-tail mixin, to advance the hold
 *       and send the request when it completes.</li>
 *   <li>{@link #drawHold} — the indicator, called by whoever draws the tile, right where it draws
 *       it. This class deliberately does not draw it itself: a screen renders its tiles inside its
 *       own translated pose (Advancements Reloaded lays its tree out that way), so a rectangle that
 *       is correct there lands somewhere else entirely if it is drawn from the screen's render
 *       tail. Drawing beside the tile's own blit keeps the wash on the tile whatever the screen
 *       does with the matrix.</li>
 * </ul>
 * Render-thread only, like {@link fr.euclesia.mcarchipelago.client.render.ArchipelagoTabIcon}.
 */
public final class HintHoldTracker {
    private static final long HOLD_MILLIS = 1500;
    // Prevents an accidental re-hold (or holding through the confirmation) from immediately
    // re-sending the same hint request.
    private static final long COOLDOWN_MILLIS = 30_000;
    // Minecraft's own purple, arriving left to right as the hold completes: the tile turning purple
    // is the "this is about to hint" signal.
    //
    // A tile's box is not tinted but redrawn in these three shades, which are that purple carrying
    // the shading of vanilla's own box sprite: its interior, bevel and bottom shade are one hue at
    // roughly 1.29x, 1x and 0.43x brightness, so the purple bar reads as the same control in
    // another colour rather than as something painted over one. WASH_ARGB is for a screen that can
    // only lay the indicator over a finished tile.
    private static final int BOX_BEVEL_ARGB = 0xFFDB00DB;
    private static final int BOX_INTERIOR_ARGB = 0xFF000000 | ChatFormatting.DARK_PURPLE.getColor();
    private static final int BOX_SHADE_ARGB = 0xFF490049;
    private static final int BOX_OUTLINE_ARGB = 0xFF000000;
    private static final int WASH_ARGB = 0x99000000 | ChatFormatting.DARK_PURPLE.getColor();

    private HintHoldTracker() {}

    // Set by reportHover during this frame's widget pass; consumed and cleared by tick().
    private static Identifier hoveredId;

    // The tile currently being held, and when the hold started.
    private static Identifier heldId;
    private static long holdStartMillis;
    private static boolean fired;

    private static final Map<Identifier, Long> lastFiredAt = new HashMap<>();

    /**
     * Called once per frame for the tile under the cursor, by whichever screen is rendering. Only
     * tiles that resolve to an active item-unlock tracker are remembered — everything else (kills,
     * bosses, structures, category roots, the tab root) can never start a hold.
     */
    public static void reportHover(Identifier id) {
        if (id == null || !isHintable(id)) {
            return;
        }
        hoveredId = id;
    }

    /**
     * Draws the hold indicator over one tile: a wash of Minecraft's purple filling left to right as
     * the hold completes. Called by whoever draws that tile, with the rectangle in the same
     * coordinates it just drew in, and does nothing unless this is the tile being held. It stops as
     * soon as the request is sent, so a player who keeps the button down sees the indicator go
     * rather than sit full — holding on does not ask again.
     */
    public static void drawHold(GuiGraphicsExtractor graphics, Identifier id, int x, int y,
                                int width, int height) {
        if (id == null || fired || !id.equals(heldId)) {
            return;
        }
        float progress = Math.min(1.0f, (System.currentTimeMillis() - holdStartMillis) / (float) HOLD_MILLIS);
        graphics.fill(x, y, x + Math.round(width * progress), y + height, WASH_ARGB);
    }

    /**
     * Redraws a tile's box in purple, over the blue one, for a screen that can draw the indicator
     * into the tile's background before its icon and text go on top. Not a tint: the box is painted
     * again in the shades above, following the shape of vanilla's box sprite (which Advancements
     * Reloaded also uses) — outline, a bevel down the top and left, flat interior, a darker shade
     * along the bottom and right — so the result looks like the bar itself is purple.
     *
     * <p>The shape is laid out from the edges rather than from fixed row numbers, so it follows a
     * box of any height: three rows in for the outline, then the bevel, then the interior.
     *
     * <p>Scissored to how far the hold has got, which is what makes it arrive across the bar. The
     * clip is applied through the pose, so a screen that draws its tree translated (Advancements
     * Reloaded does) clips in the same place it draws.
     */
    public static void drawHoldBox(GuiGraphicsExtractor graphics, Identifier id, int x, int y,
                                   int width, int height) {
        if (id == null || fired || !id.equals(heldId) || width < 6 || height < 11) {
            return;
        }
        float progress = Math.min(1.0f, (System.currentTimeMillis() - holdStartMillis) / (float) HOLD_MILLIS);
        int filled = Math.round(width * progress);
        if (filled <= 0) {
            return;
        }
        int right = x + width;
        int bottom = y + height;

        graphics.enableScissor(x, y, x + filled, bottom);
        // Outline: the top and bottom rows, the sides, and the single pixels that round the corners.
        graphics.fill(x + 2, y + 3, right - 2, y + 4, BOX_OUTLINE_ARGB);
        graphics.fill(x + 2, bottom - 4, right - 2, bottom - 3, BOX_OUTLINE_ARGB);
        graphics.fill(x, y + 5, x + 1, bottom - 5, BOX_OUTLINE_ARGB);
        graphics.fill(right - 1, y + 5, right, bottom - 5, BOX_OUTLINE_ARGB);
        graphics.fill(x + 1, y + 4, x + 2, y + 5, BOX_OUTLINE_ARGB);
        graphics.fill(x + 1, bottom - 5, x + 2, bottom - 4, BOX_OUTLINE_ARGB);
        graphics.fill(right - 2, y + 4, right - 1, y + 5, BOX_OUTLINE_ARGB);
        graphics.fill(right - 2, bottom - 5, right - 1, bottom - 4, BOX_OUTLINE_ARGB);

        graphics.fill(x + 2, y + 4, right - 2, y + 5, BOX_BEVEL_ARGB);
        graphics.fill(x + 1, y + 5, x + 2, bottom - 5, BOX_BEVEL_ARGB);
        graphics.fill(x + 2, bottom - 5, right - 2, bottom - 4, BOX_SHADE_ARGB);
        graphics.fill(right - 2, y + 5, right - 1, bottom - 5, BOX_SHADE_ARGB);

        graphics.fill(x + 2, y + 5, right - 2, bottom - 5, BOX_INTERIOR_ARGB);
        graphics.disableScissor();
    }

    private static boolean isHintable(Identifier id) {
        APTrackerRegistry.Tracker tracker = AEM.ARCHIPELAGO.client().registries().apTrackers().get(id.toString());
        return tracker != null && APTrackerRegistry.KIND_UNLOCK.equals(tracker.kind());
    }

    /** Advances the hold state machine, sending the request when it completes. Once per frame. */
    public static void tick() {
        Identifier id = hoveredId;
        hoveredId = null; // consumed; next frame's widget pass repopulates it if still hovered

        if (id == null || !isLeftMouseDown() || (heldId != null && !heldId.equals(id))) {
            heldId = null;
            fired = false;
            return;
        }
        if (heldId == null) {
            Long last = lastFiredAt.get(id);
            if (last != null && System.currentTimeMillis() - last < COOLDOWN_MILLIS) {
                return; // still cooling down; don't start a visible hold yet
            }
            heldId = id;
            holdStartMillis = System.currentTimeMillis();
            fired = false;
        }

        long elapsed = System.currentTimeMillis() - holdStartMillis;
        if (!fired && elapsed >= HOLD_MILLIS) {
            fired = true;
            lastFiredAt.put(id, System.currentTimeMillis());
            HintClient.requestHint(id);
        }
    }

    /**
     * Asks GLFW directly. Neither obvious alternative works from inside a screen:
     * {@code InputConstants.isKeyDown} is {@code glfwGetKey}, i.e. the KEYBOARD, and
     * {@code GLFW_MOUSE_BUTTON_LEFT} is 0, which is not a key; and {@code MouseHandler}'s own
     * {@code isLeftPressed} is only assigned while no screen and no overlay is open, so in the
     * advancement screen — the only place this class runs — it is permanently false.
     */
    private static boolean isLeftMouseDown() {
        long window = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

}
