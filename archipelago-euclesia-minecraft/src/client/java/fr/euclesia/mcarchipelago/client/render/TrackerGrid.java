package fr.euclesia.mcarchipelago.client.render;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.mixin.AdvancementWidgetAccessor;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lays a tracker tab (Kills, Entity Unlocks, Structure Unlocks, Knowledge) out as a gapless grid with
 * a header per section, instead of the tree the datapack's parent chains make.
 *
 * <p>The vanilla widgets stay: only their x/y move, so the frame repaint, icons, tooltips, glow, hint
 * hold and progress fill all keep working untouched. Order and sections come from
 * {@code slot_data["trackers"]} (trackers.py sorts it, and tags each tile with its {@code section}).
 * Slot data without sections keeps the tree. Screen-replacing mods (Better Advancements, …) never
 * reach this and keep the tree too.
 */
public final class TrackerGrid {
    public static final int COLUMNS = 8;          // what fits the vanilla 234 px content area
    public static final int CELL_W = 28;          // vanilla's column / row pitch
    public static final int CELL_H = 27;
    public static final int WIDTH = COLUMNS * CELL_W;
    private static final int HEADER_H = 12;
    private static final int SECTION_GAP = 4;
    private static final int OFF_SCREEN = -10_000;

    private static final Set<String> TABS = Set.of(
            APTrackerRegistry.CATEGORY_KILLS, APTrackerRegistry.CATEGORY_ENTITY_UNLOCKS,
            APTrackerRegistry.CATEGORY_STRUCTURE_UNLOCKS, APTrackerRegistry.CATEGORY_KNOWLEDGE);

    public record Header(int y, Component label) {}

    private TrackerGrid() {}

    /**
     * Places {@code widgets} (the tab's, {@code root} included) on the grid and fills {@code headers}.
     *
     * @return the content height, or -1 when the tab keeps vanilla's tree (not a tracker tab, or no
     *         sections in the slot data)
     */
    public static int layout(AdvancementWidget root, Collection<AdvancementWidget> widgets, List<Header> headers) {
        headers.clear();
        if (root == null || !TABS.contains(id(root))) {
            return -1;
        }
        Map<String, AdvancementWidget> byId = new LinkedHashMap<>();
        for (AdvancementWidget widget : widgets) {
            if (widget != root) {
                byId.put(id(widget), widget);
            }
        }
        List<AdvancementWidget> order = new ArrayList<>();
        List<String> sections = new ArrayList<>();
        AEM.ARCHIPELAGO.client().registries().apTrackers().forEach((id, tracker) -> {
            AdvancementWidget widget = byId.remove(id);
            if (widget != null) {
                order.add(widget);
                sections.add(tracker.section());
            }
        });
        if (sections.stream().allMatch(Objects::isNull)) {
            return -1;
        }
        for (AdvancementWidget rest : byId.values()) {  // a check re-parented here: last, no header
            order.add(rest);
            sections.add(null);
        }

        int y = 0;
        int col = 0;
        for (int i = 0; i < order.size(); i++) {
            String section = sections.get(i);
            if (i == 0 || !Objects.equals(section, sections.get(i - 1))) {
                if (col > 0) {
                    y += CELL_H;
                    col = 0;
                }
                if (i > 0) {
                    y += SECTION_GAP;
                }
                if (section != null) {
                    headers.add(new Header(y + 2, Component.translatableWithFallback(
                            "gui.aem.tracker_section." + section, section)));
                    y += HEADER_H;
                }
            }
            place(order.get(i), col * CELL_W, y);
            if (++col == COLUMNS) {
                col = 0;
                y += CELL_H;
            }
        }
        // The category root is the tab button; its tile would be a lone extra cell, so park it.
        place(root, OFF_SCREEN, OFF_SCREEN);
        return col > 0 ? y + CELL_H : y;
    }

    private static void place(AdvancementWidget widget, int x, int y) {
        AdvancementWidgetAccessor accessor = (AdvancementWidgetAccessor) widget;
        accessor.archipelago_euclesia$setX(x);
        accessor.archipelago_euclesia$setY(y);
    }

    private static String id(AdvancementWidget widget) {
        return ((AdvancementWidgetAccessor) widget).archipelago_euclesia$getAdvancementNode().holder().id().toString();
    }
}
