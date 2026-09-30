package fr.euclesia.mcarchipelago.client.utils;

/**
 * Mixin target strings that differ between Minecraft versions, in one place.
 *
 * <p>A mixin names what it hooks as a string, and Stonecutter's import switching never reaches inside
 * a string: 26.3 moved {@code RenderPipeline} to the new renderer package, turned {@code DisplayInfo}
 * into a record ({@code isHidden()} became {@code hidden()}) and gave {@code extractHover} another
 * {@code int}. Annotations accept compile-time constants, so the mixins reference these instead of
 * spelling the descriptors out, and this is the only file that knows the versions.
 *
 * <p>Not in a mixin package: Mixin will not load an ordinary class from one. Nothing here is checked by javac — {@code tools/check_mixin_targets.py} resolves every one of
 * them against each version's jar.
 */
public final class MixinTargets {
    //? if >=26.3 {
    /*private static final String PIPELINE = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;";
    public static final String EXTRACT_HOVER = "extractHover(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIFIII)V";
    public static final String DISPLAY_IS_HIDDEN = "Lnet/minecraft/advancements/DisplayInfo;hidden()Z";
    public static final String DISPLAY_DESCRIPTION = "description";
    *///?} else {
    private static final String PIPELINE = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;";
    public static final String EXTRACT_HOVER = "extractHover(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIFII)V";
    public static final String DISPLAY_IS_HIDDEN = "Lnet/minecraft/advancements/DisplayInfo;isHidden()Z";
    public static final String DISPLAY_DESCRIPTION = "getDescription";
    //?}

    private static final String BLIT_SPRITE = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(" + PIPELINE
            + "Lnet/minecraft/resources/Identifier;";
    /** {@code blitSprite(pipeline, sprite, x, y, width, height)} */
    public static final String BLIT_SPRITE_BOX = BLIT_SPRITE + "IIII)V";
    /** {@code blitSprite(pipeline, sprite, x, y, width, height, color)} */
    public static final String BLIT_SPRITE_BOX_TINTED = BLIT_SPRITE + "IIIII)V";
    /** {@code blitSprite(pipeline, sprite, textureWidth, textureHeight, u, v, x, y, width, height)} */
    public static final String BLIT_SPRITE_SLICE = BLIT_SPRITE + "IIIIIIII)V";

    private MixinTargets() {}
}
