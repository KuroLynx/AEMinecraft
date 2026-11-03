package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.client.mixin.AdvancementsScreenAccessor;
import fr.euclesia.mcarchipelago.client.render.TrackerGridTab;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * A search box in the advancement window's title bar, shown on the tracker tabs laid out as grids
 * ({@link fr.euclesia.mcarchipelago.client.render.TrackerGrid}). Tiles whose name contains the text
 * get a glowing outline ({@code AdvancementRenderHooks.drawSearchGlow}). The text lasts while the
 * screen is open (across tabs and resizes) and is cleared when it closes.
 */
public final class TrackerSearch {
    // Vanilla's advancement window, the same on 26.1.2 / 26.2 / 26.3.
    private static final int WINDOW_WIDTH = 252;
    private static final int WINDOW_HEIGHT = 140;
    private static final int BOX_WIDTH = 92;
    private static final int BOX_HEIGHT = 12;

    private static EditBox box;
    private static String query = "";

    private TrackerSearch() {}

    /** Adds the box to a freshly (re)built advancement screen. */
    public static void attach(AdvancementsScreen screen) {
        int left = (screen.width - WINDOW_WIDTH) / 2;
        int top = (screen.height - WINDOW_HEIGHT) / 2;
        EditBox search = new EditBox(Minecraft.getInstance().font, left + WINDOW_WIDTH - BOX_WIDTH - 8, top + 3,
                BOX_WIDTH, BOX_HEIGHT, Component.translatable("gui.aem.tracker_search.hint"));
        search.setHint(Component.translatable("gui.aem.tracker_search.hint"));
        search.setMaxLength(32);
        search.setValue(query);
        search.setResponder(text -> query = text.trim().toLowerCase(Locale.ROOT));
        box = search;
        Screens.getWidgets(screen).add(search);
        ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
            search.visible = onGridTab(screen);
            if (!search.visible && search.isFocused()) {
                search.setFocused(false);
            }
        });
        ScreenEvents.remove(screen).register(s -> {
            query = "";
            box = null;
        });
    }

    /**
     * Typing goes to the box while it has focus: otherwise the advancements key (L) would close the
     * screen mid-word. Escape still closes it.
     */
    public static boolean consumesKey(KeyEvent event) {
        return box != null && box.visible && box.isFocused() && !event.isEscape();
    }

    public static boolean keyPressed(KeyEvent event) {
        return box.keyPressed(event);
    }

    /** Whether {@code node}'s tile matches the search (only while the box is shown). */
    public static boolean matches(AdvancementNode node) {
        if (query.isEmpty() || box == null || !box.visible || node == null) {
            return false;
        }
        // 26.3 made DisplayInfo a record: getTitle() became title().
        //? if >=26.3 {
        /*String title = node.holder().value().display().map(display -> display.title().getString()).orElse("");
        *///?} else {
        String title = node.holder().value().display().map(display -> display.getTitle().getString()).orElse("");
        //?}
        return title.toLowerCase(Locale.ROOT).contains(query);
    }

    private static boolean onGridTab(AdvancementsScreen screen) {
        AdvancementTab tab = ((AdvancementsScreenAccessor) screen).archipelago_euclesia$getSelectedTab();
        return tab instanceof TrackerGridTab grid && grid.archipelago_euclesia$isGrid();
    }
}
