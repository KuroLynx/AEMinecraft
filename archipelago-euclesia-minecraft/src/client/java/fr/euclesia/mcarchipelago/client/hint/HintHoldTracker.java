package fr.euclesia.mcarchipelago.client.hint;

import com.mojang.blaze3d.platform.InputConstants;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.net.HintClient;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
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
 * compat adapter's replacement screen: {@link #reportHover} is called for every tile tested for
 * mouse-over this frame (from {@code AdvancementWidgetMixin} or an adapter's equivalent), and
 * {@link #tick} is called once per frame from that screen's render-tail mixin to advance the hold
 * state machine and draw the progress indicator. Render-thread only, like
 * {@link fr.euclesia.mcarchipelago.client.render.ArchipelagoTabIcon}.
 */
public final class HintHoldTracker {
    private static final long HOLD_MILLIS = 1500;
    // Prevents an accidental re-hold (or holding through the confirmation) from immediately
    // re-sending the same hint request.
    private static final long COOLDOWN_MILLIS = 30_000;
    private static final int BAR_HEIGHT = 2;
    private static final int BAR_COLOR = 0xFF4CD964;
    private static final int TILE_SIZE = 26;

    private HintHoldTracker() {}

    // Set by reportHover during this frame's widget pass; consumed and cleared by tick().
    private static Identifier hoveredId;
    private static int hoveredX;
    private static int hoveredY;

    // The tile currently being held, and when the hold started.
    private static Identifier heldId;
    private static long holdStartMillis;
    private static boolean fired;

    private static final Map<Identifier, Long> lastFiredAt = new HashMap<>();

    /**
     * Called for every advancement tile tested for mouse-over this frame, by whichever screen is
     * rendering. Only tiles that resolve to an active item-unlock tracker are remembered —
     * everything else (kills, bosses, structures, category roots, the tab root) can never start
     * a hold.
     */
    public static void reportHover(Identifier id, int x, int y, boolean hovered) {
        if (!hovered || id == null || !isHintable(id)) {
            return;
        }
        hoveredId = id;
        hoveredX = x;
        hoveredY = y;
    }

    private static boolean isHintable(Identifier id) {
        APTrackerRegistry.Tracker tracker = AEM.ARCHIPELAGO.client().registries().apTrackers().get(id.toString());
        return tracker != null && APTrackerRegistry.KIND_UNLOCK.equals(tracker.kind());
    }

    /** Advances the hold state machine and draws the progress indicator. One call per rendered frame. */
    public static void tick(GuiGraphicsExtractor graphics) {
        Identifier id = hoveredId;
        int x = hoveredX;
        int y = hoveredY;
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
        drawProgress(graphics, x, y, Math.min(1.0f, elapsed / (float) HOLD_MILLIS));
    }

    private static boolean isLeftMouseDown() {
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_MOUSE_BUTTON_1);
    }

    private static void drawProgress(GuiGraphicsExtractor graphics, int x, int y, float progress) {
        int width = Math.round(TILE_SIZE * progress);
        graphics.fill(x, y + TILE_SIZE, x + width, y + TILE_SIZE + BAR_HEIGHT, BAR_COLOR);
    }
}
