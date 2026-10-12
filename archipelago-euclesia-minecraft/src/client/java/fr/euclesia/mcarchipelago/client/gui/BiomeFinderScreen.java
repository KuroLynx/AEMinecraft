package fr.euclesia.mcarchipelago.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import fr.euclesia.mcarchipelago.client.net.BiomeFinderClient;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderService;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderTrackerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Opened from the Biome Finder keybind or the inventory-screen button (see
 * {@link fr.euclesia.mcarchipelago.client.AEMClient} / {@link AEMScreenButtons}).
 *
 * <p>Left: a top-down map of the biomes around you, north up, you at the centre (sampled by the server,
 * see {@link BiomeFinderService#sampleMap}). Click a patch to track exactly that spot. Right: every biome
 * the dimension can generate, the ones on the map first and with their map colour, then the rest; click
 * one to locate the nearest (the 6400-block search), hover one to light it up on the map. Under that,
 * "Currently tracking" with a ✕ that stops tracking — reaching the biome also clears it by itself.
 *
 * <p>Both halves go through the server ({@link BiomeFinderClient}), so the data arrives a round trip
 * after the screen opens: it draws a loading line until then, and fills itself in from {@link #tick()}.
 */
public final class BiomeFinderScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int ROW_HEIGHT = 20;
    private static final int LEGEND_ROW = 12;
    private static final int LEGEND_MIN_WIDTH = 140;
    private static final int LEGEND_MAX_WIDTH = 200;
    private static final int SWATCH = 8;
    private static final int BORDER = 0xFF000000;
    private static final int HOVER = 0x30FFFFFF;

    /** A biome in the legend: id, name, map colour, how many map cells it covers, filter haystack. */
    private record Entry(int index, Identifier id, Component name, int color, int cells, String haystack) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    /** {@link #entries} by biome index, for the map's per-cell lookups. */
    private Entry[] byIndex = new Entry[0];
    private BiomeFinderClient.BiomeMap map;

    private EditBox searchField;
    private Button clearButton;
    private String lastQuery = "";
    private int scroll;
    /** False until the server's answer has arrived; the list is empty rather than absent. */
    private boolean loaded;

    // Layout, recomputed in init().
    private int mapX;
    private int mapY;
    private int mapSide;
    private int legendX;
    private int legendWidth;
    private int rowsTop;
    private int rowsBottom;
    private int trackingY;

    public BiomeFinderScreen() {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.translatable("item.aem.biome_finder"));
        // Asked for once per opening — init() also runs on resize, which must not restart the wait.
        BiomeFinderClient.requestList();
    }

    @Override
    protected void init() {
        loadEntries();

        int top = MARGIN + 14;
        int closeY = this.height - MARGIN - ROW_HEIGHT;
        trackingY = closeY - ROW_HEIGHT - 2;
        int available = closeY - 6 - top;
        mapSide = Math.max(0, Math.min(available, this.width - 3 * MARGIN - LEGEND_MIN_WIDTH));
        legendWidth = Math.min(LEGEND_MAX_WIDTH, this.width - 3 * MARGIN - mapSide);
        int left = (this.width - (mapSide + MARGIN + legendWidth)) / 2;
        mapX = left;
        mapY = top;
        legendX = left + mapSide + MARGIN;
        rowsTop = top + ROW_HEIGHT + 4;
        rowsBottom = trackingY - 2;

        String query = searchField == null ? "" : searchField.getValue();
        searchField = new EditBox(this.font, legendX, top, legendWidth, ROW_HEIGHT,
                Component.translatable("gui.aem.biome_finder.search"));
        searchField.setHint(Component.translatable("gui.aem.biome_finder.search_hint"));
        searchField.setMaxLength(128);
        searchField.setValue(query);
        addRenderableWidget(searchField);
        setInitialFocus(searchField);

        clearButton = addRenderableWidget(Button.builder(Component.literal("✕"), button -> {
                    BiomeFinderClient.clear();
                    button.visible = false;
                })
                .tooltip(Tooltip.create(Component.translatable("gui.aem.biome_finder.stop")))
                .bounds(legendX + legendWidth - ROW_HEIGHT, trackingY, ROW_HEIGHT, ROW_HEIGHT)
                .build());
        clearButton.visible = tracked() != null;

        addRenderableWidget(Button.builder(Component.translatable("gui.aem.common.close"), button -> onClose())
                .bounds(legendX, closeY, legendWidth, ROW_HEIGHT)
                .build());

        applyFilter();
    }

    /** Rebuilds {@link #entries} (and the map) from whatever the server has sent us so far. */
    private void loadEntries() {
        entries.clear();
        List<Identifier> biomes = BiomeFinderClient.biomes();
        loaded = biomes != null;
        if (!loaded) {
            return;
        }
        map = BiomeFinderClient.map();
        int[] counts = new int[biomes.size()];
        if (map != null) {
            for (int row = 0; row < map.size(); row++) {
                for (int col = 0; col < map.size(); col++) {
                    int index = map.at(col, row);
                    if (index >= 0 && index < counts.length) {
                        counts[index]++;
                    }
                }
            }
        }
        byIndex = new Entry[biomes.size()];
        for (int i = 0; i < biomes.size(); i++) {
            Identifier id = biomes.get(i);
            Component name = Component.translatable(BiomeFinderService.biomeTranslationKey(id));
            String haystack = (name.getString() + " " + id).toLowerCase(Locale.ROOT);
            byIndex[i] = new Entry(i, id, name, BiomeMapColors.argb(id), counts[i], haystack);
            entries.add(byIndex[i]);
        }
        // What you can see first, biggest first; then everything else by name.
        entries.sort(Comparator.comparingInt((Entry e) -> -e.cells())
                .thenComparing(e -> e.name().getString()));
    }

    private void applyFilter() {
        String query = searchField.getValue().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (Entry entry : entries) {
            if (query.isEmpty() || entry.haystack().contains(query)) {
                filtered.add(entry);
            }
        }
        scroll = 0;
    }

    private int visibleRows() {
        return Math.max(1, (rowsBottom - rowsTop) / LEGEND_ROW);
    }

    /** The local player's tracked biome, or null. */
    private static BiomeFinderTrackerState.Target tracked() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : BiomeFinderTrackerState.get().target(player.getUUID());
    }

    // --- map geometry --------------------------------------------------------------------------

    private boolean hasMap() {
        return map != null && mapSide > 0;
    }

    /** Screen x of the left edge of map column {@code col} (also works for rows, from {@link #mapY}). */
    private int edge(int origin, int cell) {
        return origin + cell * mapSide / map.size();
    }

    /** The map cell under the mouse as {col, row}, or null when not over the map. */
    private int[] cellAt(double mouseX, double mouseY) {
        if (!hasMap() || mouseX < mapX || mouseY < mapY || mouseX >= mapX + mapSide || mouseY >= mapY + mapSide) {
            return null;
        }
        int col = (int) ((mouseX - mapX) * map.size() / mapSide);
        int row = (int) ((mouseY - mapY) * map.size() / mapSide);
        return new int[] {Math.min(col, map.size() - 1), Math.min(row, map.size() - 1)};
    }

    private int blockX(int col) {
        return map.centerX() + (col - map.size() / 2) * map.step();
    }

    private int blockZ(int row) {
        return map.centerZ() + (row - map.size() / 2) * map.step();
    }

    private Entry entryFor(int index) {
        return index >= 0 && index < byIndex.length ? byIndex[index] : null;
    }

    /** The legend entry under the mouse, or null. */
    private Entry legendAt(double mouseX, double mouseY) {
        if (mouseX < legendX || mouseX >= legendX + legendWidth || mouseY < rowsTop) {
            return null;
        }
        int line = (int) ((mouseY - rowsTop) / LEGEND_ROW);
        int index = scroll + line;
        return line < visibleRows() && index < filtered.size() ? filtered.get(index) : null;
    }

    // --- input ---------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int[] cell = cellAt(event.x(), event.y());
            if (cell != null && map.at(cell[0], cell[1]) >= 0) {
                BiomeFinderClient.pickSpot(blockX(cell[0]), blockZ(cell[1]));
                onClose();
                return true;
            }
            Entry entry = legendAt(event.x(), event.y());
            if (entry != null) {
                BiomeFinderClient.pick(entry.id());
                onClose();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= legendX && filtered.size() > visibleRows()) {
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), filtered.size() - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void tick() {
        super.tick();
        // The server clears the target on arrival or after the ✕; follow it rather than our own guess.
        clearButton.visible = tracked() != null;
        if (!loaded && BiomeFinderClient.biomes() != null) {
            loadEntries();
            applyFilter();
        }
        if (!searchField.getValue().equals(lastQuery)) {
            lastQuery = searchField.getValue();
            applyFilter();
        }
    }

    // --- drawing -------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, MARGIN, 0xFFFFFFFF);

        Entry hoveredLegend = legendAt(mouseX, mouseY);
        if (hasMap()) {
            drawMap(graphics, hoveredLegend == null ? -1 : hoveredLegend.index());
            drawMapHover(graphics, mouseX, mouseY);
        } else if (mapSide > 0) {
            String key = loaded ? "gui.aem.biome_finder.no_map" : "gui.aem.biome_finder.loading";
            graphics.centeredText(this.font, Component.translatable(key).getString(),
                    mapX + mapSide / 2, mapY + mapSide / 2, 0xFFA0A0A0);
        }
        drawLegend(graphics, hoveredLegend);
        drawTracking(graphics);
    }

    /** The map, one horizontal run per stretch of same-biome cells; {@code highlight} dims the rest. */
    private void drawMap(GuiGraphicsExtractor graphics, int highlight) {
        graphics.fill(mapX - 1, mapY - 1, mapX + mapSide + 1, mapY + mapSide + 1, BORDER);
        int size = map.size();
        for (int row = 0; row < size; row++) {
            int top = edge(mapY, row);
            int bottom = edge(mapY, row + 1);
            if (bottom <= top) {
                continue;
            }
            int start = 0;
            while (start < size) {
                int index = map.at(start, row);
                int end = start + 1;
                while (end < size && map.at(end, row) == index) {
                    end++;
                }
                Entry entry = entryFor(index);
                int color = entry == null ? BORDER : entry.color();
                if (highlight >= 0 && index != highlight) {
                    color = dim(color);
                }
                graphics.fill(edge(mapX, start), top, edge(mapX, end), bottom, color);
                start = end;
            }
        }

        // You: a white dot ringed in black at the centre.
        int cx = edge(mapX, size / 2);
        int cy = edge(mapY, size / 2);
        graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, BORDER);
        graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

        // The tracked spot, if it is on this map: a red dot.
        BiomeFinderTrackerState.Target target = tracked();
        LocalPlayer player = Minecraft.getInstance().player;
        if (target != null && player != null && target.dimension().equals(player.level().dimension())) {
            double col = (target.pos().getX() - map.centerX()) / (double) map.step() + size / 2.0;
            double row = (target.pos().getZ() - map.centerZ()) / (double) map.step() + size / 2.0;
            if (col >= 0 && row >= 0 && col < size && row < size) {
                int tx = mapX + (int) (col * mapSide / size);
                int ty = mapY + (int) (row * mapSide / size);
                graphics.fill(tx - 2, ty - 2, tx + 3, ty + 3, BORDER);
                graphics.fill(tx - 1, ty - 1, tx + 2, ty + 2, 0xFFFF5555);
            }
        }
    }

    private static int dim(int argb) {
        int r = (argb >> 16 & 0xFF) / 3;
        int g = (argb >> 8 & 0xFF) / 3;
        int b = (argb & 0xFF) / 3;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private void drawMapHover(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int[] cell = cellAt(mouseX, mouseY);
        if (cell == null) {
            return;
        }
        Entry entry = entryFor(map.at(cell[0], cell[1]));
        if (entry == null) {
            return;
        }
        graphics.fill(edge(mapX, cell[0]), edge(mapY, cell[1]), edge(mapX, cell[0] + 1) + 1,
                edge(mapY, cell[1] + 1) + 1, HOVER);
        graphics.setTooltipForNextFrame(Component.translatable("gui.aem.biome_finder.map_hover",
                entry.name(), blockX(cell[0]), blockZ(cell[1])), mouseX, mouseY);
    }

    private void drawLegend(GuiGraphicsExtractor graphics, Entry hovered) {
        if (!loaded) {
            graphics.text(this.font, Component.translatable("gui.aem.biome_finder.loading").getString(),
                    legendX, rowsTop, 0xFFA0A0A0);
            return;
        }
        if (entries.isEmpty()) {
            graphics.text(this.font, Component.translatable("gui.aem.biome_finder.empty").getString(),
                    legendX, rowsTop, 0xFFFF5555);
            return;
        }
        int rows = Math.min(visibleRows(), filtered.size() - scroll);
        for (int i = 0; i < rows; i++) {
            Entry entry = filtered.get(scroll + i);
            int y = rowsTop + i * LEGEND_ROW;
            if (entry == hovered) {
                graphics.fill(legendX, y, legendX + legendWidth, y + LEGEND_ROW, HOVER);
            }
            int swatchY = y + (LEGEND_ROW - SWATCH) / 2;
            graphics.fill(legendX + 1, swatchY, legendX + 1 + SWATCH, swatchY + SWATCH, BORDER);
            graphics.fill(legendX + 2, swatchY + 1, legendX + SWATCH, swatchY + SWATCH - 1, entry.color());
            // Off-map biomes are still pickable (nearest search), just greyed: you can't see them yet.
            int textColor = entry.cells() > 0 ? 0xFFFFFFFF : 0xFFA0A0A0;
            String name = this.font.plainSubstrByWidth(entry.name().getString(), legendWidth - SWATCH - 6);
            graphics.text(this.font, name, legendX + SWATCH + 5, y + 2, textColor);
        }
    }

    private void drawTracking(GuiGraphicsExtractor graphics) {
        BiomeFinderTrackerState.Target target = tracked();
        if (target == null || !clearButton.visible) {
            return;
        }
        Identifier biome = Identifier.tryParse(target.biomeId());
        Component name = biome == null ? Component.literal(target.biomeId())
                : Component.translatable(BiomeFinderService.biomeTranslationKey(biome));
        String line = Component.translatable("gui.aem.biome_finder.tracking", name,
                target.pos().getX(), target.pos().getZ()).getString();
        line = this.font.plainSubstrByWidth(line, legendWidth - ROW_HEIGHT - 4); // stop short of the ✕
        int textY = trackingY + (ROW_HEIGHT - this.font.lineHeight) / 2 + 1;
        graphics.text(this.font, line, legendX, textY, 0xFFFFFFFF);
    }
}
