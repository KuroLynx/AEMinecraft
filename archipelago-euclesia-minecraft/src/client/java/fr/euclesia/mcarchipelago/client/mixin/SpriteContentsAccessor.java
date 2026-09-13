package fr.euclesia.mcarchipelago.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a stitched sprite's source image — the artwork as loaded, before it was packed into the
 * atlas and mipmapped. {@code RecoloredSprites} reads it to repaint a sprite in another colour.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage archipelago_euclesia$originalImage();
}
