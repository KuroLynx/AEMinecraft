package fr.euclesia.mcarchipelago.client.render;

import fr.euclesia.mcarchipelago.client.logic.LogicColors;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Screen-agnostic tile-rendering logic for the Archipelago advancement tabs.
 *
 * <p>This is the single implementation both the vanilla advancement-screen mixins
 * ({@code AdvancementTabMixin}, {@code AdvancementTabTypeMixin}, {@code AdvancementWidgetMixin})
 * and any compat adapter for a screen-replacing mod (e.g.
 * {@code client.compat.advancementsreloaded}) call into — so adding support for another mod
 * that reimplements the advancement screen means writing thin mixins that call these methods at
 * that mod's own render call sites, never duplicating the logic itself.
 */
public final class AdvancementRenderHooks {
    private AdvancementRenderHooks() {}

    /** Whether {@code id} is the main Archipelago tab root (gets the logo instead of its item icon). */
    public static boolean isTabRoot(Identifier id) {
        return id != null && APTrackerRegistry.TAB_ROOT_ID.equals(id.toString());
    }

    public static void drawTabLogo(GuiGraphicsExtractor graphics, int x, int y) {
        ArchipelagoTabIcon.draw(graphics, x, y);
    }

    /** @return the 0xRRGGBB frame tint for {@code id}, or {@code null} to keep the original frame. */
    public static Integer frameColor(Identifier id) {
        return id == null ? null : LogicColors.rgb(LogicProviders.stateFor(id));
    }

    /** @return {@code true} if a custom tile icon was drawn (caller should skip its own icon draw). */
    public static boolean tryDrawTileIcon(GuiGraphicsExtractor graphics, Identifier id, int x, int y) {
        return id != null && TrackerIconRenderer.tryRenderIcon(graphics, id, x, y);
    }

    /** Hidden tracker tiles are sent by the server on purpose; always treat them as visible/hoverable. */
    public static boolean forceVisible() {
        return true;
    }
}
