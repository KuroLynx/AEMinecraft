package fr.euclesia.mcarchipelago.client.report;

import fr.euclesia.mcarchipelago.client.gui.LogicReportScreen;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.net.LogicReportPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;

/**
 * "Pick the advancement that is wrong": the report button arms this mode, and the next left click
 * on a tile opens {@link LogicReportScreen} for it instead of doing what the click normally does.
 *
 * <p>The tile under the cursor comes from the same per-frame hover feed as hold-to-hint
 * ({@code HintHoldTracker.reportHover}), so every screen that feeds that one feeds this. A click
 * lands between frames, so the hover of the last finished frame is the one that counts.
 * Render-thread only.
 */
public final class LogicReportMode {
    private static boolean armed;
    private static Identifier hoveredThisFrame;
    private static Identifier hoveredLastFrame;

    private LogicReportMode() {}

    public static boolean armed() {
        return armed;
    }

    public static void toggle() {
        armed = !armed;
    }

    public static void disarm() {
        armed = false;
    }

    public static void hover(Identifier id) {
        hoveredThisFrame = id;
    }

    public static void endFrame() {
        hoveredLastFrame = hoveredThisFrame;
        hoveredThisFrame = null;
    }

    /** Fabric's allowMouseClick: {@code false} swallows the click that picked a tile. */
    public static boolean allowClick(Screen screen, MouseButtonEvent event) {
        if (!armed) {
            return true;
        }
        if (event.button() != 0) {
            armed = false; // any other button backs out
            return false;
        }
        if (hoveredLastFrame == null) {
            return true; // not on a tile: let the report button (or a tab) take the click
        }
        armed = false;
        Minecraft.getInstance().setScreen(new LogicReportScreen(screen, hoveredLastFrame));
        return false;
    }

    public static void send(Identifier id, String kind, String note) {
        if (ClientPlayNetworking.canSend(LogicReportPayload.TYPE)) {
            ClientPlayNetworking.send(new LogicReportPayload(id.toString(), kind, note,
                    LogicProviders.stateFor(id).name()));
        }
    }
}
