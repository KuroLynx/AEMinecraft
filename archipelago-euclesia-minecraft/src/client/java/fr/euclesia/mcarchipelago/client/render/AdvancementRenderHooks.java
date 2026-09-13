package fr.euclesia.mcarchipelago.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import fr.euclesia.mcarchipelago.client.logic.LogicColors;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Optional;

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

    /** @return the 0xRRGGBB logic colour for {@code id}, or {@code null} to keep the frame as drawn. */
    public static Integer frameColor(Identifier id) {
        return id == null ? null : LogicColors.rgb(LogicProviders.stateFor(id));
    }

    /**
     * Draws an advancement tile's frame in its logic colour — the one call every screen's frame
     * redirect goes through, vanilla and compat adapters alike.
     *
     * <p>The frame is repainted rather than tinted ({@link RecoloredSprites}): the sprite's own
     * pixels are rewritten in the state's hue, keeping its shape, its bevels and its shading. A
     * tint could not do this job — it multiplies, so it can only darken, and it cannot put a colour
     * into artwork that has no such channel. Repainting also means the frame a tile normally has is
     * the frame it keeps: there is no need to swap a completed tile's gold frame for the plain one
     * first, the way tinting required.
     *
     * <p>{@code plainArgb} is what to draw with when the tile has no logic colour — pass
     * {@link #NO_TINT} for the sprite as-is, or a screen's own colour where it had one. It is also
     * what an unreadable sprite falls back to, rather than a tint.
     */
    public static void drawFrame(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                 int x, int y, int width, int height, Integer rgb, int plainArgb) {
        if (rgb != null) {
            Optional<RecoloredSprites.Repaint> repaint = RecoloredSprites.get(graphics, sprite, rgb);
            if (repaint.isPresent()) {
                graphics.blit(pipeline, repaint.get().texture(), x, y, 0.0F, 0.0F, width, height,
                        repaint.get().width(), repaint.get().height());
                return;
            }
            // Nothing readable behind the sprite, which should not happen now that the pixels come
            // from the atlas. Draw it plainly rather than tinting: a frame in not-quite the state
            // colour reads as a state it is not, which is worse than a frame with no state on it.
        }
        if (plainArgb == NO_TINT) {
            graphics.blitSprite(pipeline, sprite, x, y, width, height);
        } else {
            graphics.blitSprite(pipeline, sprite, x, y, width, height, plainArgb);
        }
    }

    /** {@code plainArgb} for a screen that draws its uncoloured frames with no colour of its own. */
    public static final int NO_TINT = 0;

    /** Drops repainted frames after a resource reload — the artwork behind them may have changed. */
    public static void clearRepaints() {
        RecoloredSprites.clear();
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
