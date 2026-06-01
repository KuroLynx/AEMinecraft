package fr.euclesia.mcarchipelago.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * In-game HUD element drawing the Archipelago connection sphere (see
 * {@link ConnectionStatusIndicator}). A matching screen overlay covers menus, where the HUD is not
 * rendered, so the sphere is always visible.
 */
public final class ConnectionStatusHud implements HudElement {
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        ConnectionStatusIndicator.draw(graphics);
    }
}
