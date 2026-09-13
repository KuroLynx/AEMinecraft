package fr.euclesia.mcarchipelago.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.mixin.GuiSpritesAccessor;
import fr.euclesia.mcarchipelago.client.mixin.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Repaints a GUI sprite in another colour and hands back a texture to draw instead of it.
 *
 * <p>Not a tint. Blitting a sprite with a colour multiplies it, which can only ever darken and can
 * never introduce a channel the artwork does not already have — a blue box cannot be multiplied
 * purple, and a light grey over a grey frame changes nothing. This reads the sprite's own pixels
 * and writes new ones: every pixel keeps its alpha and its brightness relative to the sprite's main
 * colour, and takes the new hue. Outlines stay black, bevels stay bright, shadows stay dark, and
 * the shape is exactly the artwork's, whatever mod or resource pack supplied it.
 *
 * <p>"Relative to the sprite's main colour" means its most common opaque pixel, found per sprite:
 * that colour comes out as exactly the requested one, and everything else keeps its ratio to it. So
 * a frame's flat face becomes the state colour, its white bevel a lighter shade of it and its dark
 * edge a deeper one, in the proportions the artist drew.
 *
 * <p>The pixels come from the GUI atlas, the same place {@code blitSprite} resolves a sprite from,
 * so what is repainted is exactly what would have been drawn — including a sprite a resource pack
 * replaced, or one a mod built at runtime and stitched in rather than shipping as a file.
 *
 * <p>Results are cached per sprite and colour — a handful of 26x26 textures — and dropped on a
 * resource reload, since the artwork behind them may have just changed. Repainted sprites are drawn
 * as plain textures, so this suits a sprite drawn at its own size (a tile frame); a nine-sliced or
 * tiled one would lose its scaling metadata.
 */
public final class RecoloredSprites {
    /** A repainted sprite: the texture to draw, and the size to draw it from. */
    public record Repaint(Identifier texture, int width, int height) {}

    private static final Map<String, Optional<Repaint>> CACHE = new HashMap<>();

    private RecoloredSprites() {}

    /**
     * @return {@code sprite} repainted in {@code rgb}, or empty if its artwork could not be read
     *         (a sprite with no png behind it — the caller should fall back to drawing it plainly).
     */
    public static Optional<Repaint> get(GuiGraphicsExtractor graphics, Identifier sprite, int rgb) {
        if (sprite == null) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(sprite + "#" + rgb, ignored -> build(graphics, sprite, rgb));
    }

    /** Drops every repaint, so the next draw rebuilds from the artwork now in play. */
    public static void clear() {
        CACHE.clear();
    }

    private static Optional<Repaint> build(GuiGraphicsExtractor graphics, Identifier sprite, int rgb) {
        NativeImage source;
        try {
            TextureAtlas atlas = ((GuiSpritesAccessor) graphics).archipelago_euclesia$guiSprites();
            SpriteContents contents = atlas.getSprite(sprite).contents();
            source = ((SpriteContentsAccessor) (Object) contents).archipelago_euclesia$originalImage();
        } catch (Exception e) {
            AEM.LOGGER.warn("[AEM] could not read sprite {} to repaint it: {}", sprite, e.toString());
            return Optional.empty();
        }
        if (source == null) {
            return Optional.empty();
        }

        int reference = dominantBrightness(source);
        if (reference <= 0) {
            return Optional.empty();
        }
        // A copy: the original belongs to the atlas and is drawn from as it is.
        NativeImage image = new NativeImage(source.getWidth(), source.getHeight(), false);
        image.copyFrom(source);
        repaint(image, rgb, reference);

        Identifier texture = Identifier.fromNamespaceAndPath(AEM.MOD_ID,
                "repainted/" + sprite.getNamespace() + "/" + sprite.getPath() + "_"
                        + Integer.toHexString(rgb));
        Minecraft.getInstance().getTextureManager()
                .register(texture, new DynamicTexture(texture::toString, image));
        return Optional.of(new Repaint(texture, image.getWidth(), image.getHeight()));
    }

    /** Brightness of the sprite's most common opaque colour — what the requested colour maps onto. */
    private static int dominantBrightness(NativeImage image) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getPixel(x, y);
                if ((argb >>> 24) < 0xFF) {
                    continue;
                }
                counts.merge(argb & 0xFFFFFF, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> brightness(entry.getKey()))
                .orElse(0);
    }

    private static void repaint(NativeImage image, int rgb, int reference) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getPixel(x, y);
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    continue;
                }
                float scale = brightness(argb & 0xFFFFFF) / (float) reference;
                image.setPixel(x, y, (alpha << 24)
                        | (scaled(red, scale) << 16)
                        | (scaled(green, scale) << 8)
                        | scaled(blue, scale));
            }
        }
    }

    /** Perceptual brightness, so a coloured sprite's shading survives being given another hue. */
    private static int brightness(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return Math.round(0.2126f * red + 0.7152f * green + 0.0722f * blue);
    }

    private static int scaled(int channel, float scale) {
        return Math.clamp(Math.round(channel * scale), 0, 0xFF);
    }
}
