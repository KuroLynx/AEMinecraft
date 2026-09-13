package fr.euclesia.mcarchipelago.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A button whose face is an item, drawn as a copy of vanilla's recipe-book button — same rounded
 * outline, bevel and panel colours, in both its normal and hovered flavours — rather than the dark
 * standard button background, so it belongs inside a container screen instead of looking like an
 * options-menu widget. Painted rather than textured, and the item stands in for a GUI sprite, so
 * the whole control ships no assets. {@code message} is kept for narration and never drawn, the way
 * vanilla's icon-only {@code SpriteIconButton} treats its own label.
 */
public final class ItemIconButton extends Button {
    private static final int ICON_SIZE = 16;
    // Read off vanilla's own recipe-book button sprite (recipe_book/button.png and its
    // _highlighted variant), so this sits beside it as the same control in two flavours: black
    // outline with the four corners notched off, white bevel top-left, dark bevel bottom-right,
    // flat face — grey normally, blue while hovered or focused.
    private static final int OUTLINE_ARGB = 0xFF000000;
    private static final int BEVEL_LIGHT_ARGB = 0xFFFFFFFF;
    private static final int BEVEL_DARK_ARGB = 0xFF555555;
    private static final int FACE_ARGB = 0xFFC6C6C6;
    private static final int OUTLINE_HOVER_ARGB = 0xFF00073E;
    private static final int BEVEL_DARK_HOVER_ARGB = 0xFF343E75;
    private static final int FACE_HOVER_ARGB = 0xFF8892C9;

    private final Item item;
    // Built on first draw, not in the constructor: a button can be created before the item
    // components are bound, and an ItemStack made that early throws "Components not bound yet".
    private ItemStack icon;

    public ItemIconButton(int x, int y, int width, int height, Component message, Item item, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.item = item;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHoveredOrFocused();
        int outline = hovered ? OUTLINE_HOVER_ARGB : OUTLINE_ARGB;
        int bevelDark = hovered ? BEVEL_DARK_HOVER_ARGB : BEVEL_DARK_ARGB;
        int face = hovered ? FACE_HOVER_ARGB : FACE_ARGB;

        int left = getX();
        int top = getY();
        int right = left + getWidth();
        int bottom = top + getHeight();

        // Outline, stopping two pixels short of each corner — that notch is what rounds the button.
        graphics.fill(left + 2, top, right - 2, top + 1, outline);
        graphics.fill(left + 2, bottom - 1, right - 2, bottom, outline);
        graphics.fill(left, top + 1, left + 1, bottom - 1, outline);
        graphics.fill(right - 1, top + 1, right, bottom - 1, outline);

        graphics.fill(left + 2, top + 1, right - 2, top + 2, BEVEL_LIGHT_ARGB);
        graphics.fill(left + 1, top + 2, left + 2, bottom - 2, BEVEL_LIGHT_ARGB);
        graphics.fill(left + 2, bottom - 2, right - 2, bottom - 1, bevelDark);
        graphics.fill(right - 2, top + 1, right - 1, bottom - 1, bevelDark);

        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, face);

        graphics.item(icon(),
                left + (getWidth() - ICON_SIZE) / 2,
                top + (getHeight() - ICON_SIZE) / 2);
    }

    private ItemStack icon() {
        if (icon == null) {
            icon = new ItemStack(item);
        }
        return icon;
    }
}
