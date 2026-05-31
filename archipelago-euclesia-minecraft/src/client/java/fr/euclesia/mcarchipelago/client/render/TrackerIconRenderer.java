package fr.euclesia.mcarchipelago.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a custom head icon for tracker-tab mob tiles (kill / boss / mob-unlock), from a texture
 * shipped in the mod resources: {@code assets/aem/textures/gui/tracker/mob/<slug>.png} where
 * {@code <slug>} is the mob id path (e.g. {@code zombie}). The same head texture is reused across
 * a mob's kill/boss/unlock tiles.
 *
 * <p>If no texture is provided for a mob, this returns {@code false} so the caller falls back to
 * the advancement's item icon (the spawn egg from the datapack). Structures, the category
 * sub-roots and the tab root always fall back to their item icon.
 */
public final class TrackerIconRenderer {
    private static final String NAMESPACE = "aem";
    private static final int ICON = 16;

    // slug -> texture id if a head png exists, or ABSENT. Cleared if the resource pack set changes.
    private static final Map<String, Identifier> CACHE = new HashMap<>();
    private static final Identifier ABSENT = Identifier.fromNamespaceAndPath(NAMESPACE, "absent");

    private TrackerIconRenderer() {}

    /**
     * @return {@code true} if a head texture was drawn for this advancement (caller skips the item
     *         icon); {@code false} to fall back to the item icon.
     */
    public static boolean tryRenderIcon(GuiGraphicsExtractor graphics, Identifier advancementId, int x, int y) {
        if (!NAMESPACE.equals(advancementId.getNamespace())) {
            return false;
        }
        String path = advancementId.getPath(); // e.g. "kill/zombie"
        int slash = path.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String kind = path.substring(0, slash);
        if (!kind.equals("kill") && !kind.equals("boss") && !kind.equals("unlock_mob")) {
            return false; // structures / categories / root keep their item icon
        }

        Identifier texture = textureFor(path.substring(slash + 1));
        if (texture == null) {
            return false;
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, ICON, ICON, ICON, ICON);
        return true;
    }

    private static Identifier textureFor(String slug) {
        Identifier cached = CACHE.get(slug);
        if (cached != null) {
            return cached == ABSENT ? null : cached;
        }
        Identifier texture = Identifier.fromNamespaceAndPath(NAMESPACE, "textures/gui/tracker/mob/" + slug + ".png");
        boolean exists = Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
        CACHE.put(slug, exists ? texture : ABSENT);
        return exists ? texture : null;
    }
}
