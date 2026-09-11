package fr.euclesia.mcarchipelago.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the GUI sprite atlas a {@code GuiGraphicsExtractor} draws sprites from, so a sprite about
 * to be blitted can be read back as pixels (see {@code RecoloredSprites}). This is the same atlas
 * {@code blitSprite} itself resolves against, so what is read is exactly what would have been drawn
 * — including whatever the active resource packs replaced it with.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiSpritesAccessor {
    @Accessor("guiSprites")
    TextureAtlas archipelago_euclesia$guiSprites();
}
