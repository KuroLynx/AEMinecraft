package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.dump.DumpDataSource;
import fr.euclesia.mcarchipelago.client.dump.DumpDataSource.DatapackInfo;
import fr.euclesia.mcarchipelago.client.dump.HeadlessEntitiesDump;
import fr.euclesia.mcarchipelago.server.command.PackDump;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Content-pack dump panel — pick which source datapacks to fold in, tick which files to export, and
 * dump them to {@code <gameDir>/aem/} with no world or connection. Reads the datapacks via a
 * standalone {@link DumpDataSource} resource manager, so it works straight from the title menu; the
 * dump itself runs on a background thread.
 *
 * <p>Layout, top to bottom: a bordered, scrollable datapack list styled like the vanilla pack-select
 * screen (icon + title + description, click a row to toggle inclusion, selected rows highlighted);
 * the file-type checkboxes in a two-column grid; a primary Dump button; then a row of secondary
 * actions (Open Dump Folder / Open Source Folder / Back).
 */
public final class DumpScreen extends Screen {

    private static final int PANEL_WIDTH = 248;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 5;
    private static final int ROW_GRID = 20;
    private static final int PACK_ROW_H = 30;
    private static final int VISIBLE_PACK_ROWS = 2;
    private static final int GRID_COLS = 2;
    private static final int TITLE_SPACE = 16;

    /** Mob registry: not a {@link PackDump} file (it needs a live world), dumped via a throwaway one. */
    private static final String ENTITIES = "entities";

    /** Set by {@link HeadlessEntitiesDump} just before it rebuilds this screen, so the entities-dump
     *  result shows once we return from the temp world; consumed (cleared) on the next {@link #init}. */
    public static volatile String pendingStatus;

    // colours (ARGB) — tuned to read like the vanilla pack-selection list
    private static final int LIST_BORDER   = 0xFF000000;
    private static final int LIST_BG       = 0xC0101010;
    private static final int ROW_HOVER     = 0x33FFFFFF;
    private static final int ROW_SELECTED  = 0x554C8C2B;
    private static final int SELECT_BORDER = 0xFF6FB13A;
    private static final int ICON_BG       = 0xFF2B2B2B;
    private static final int ICON_BORDER   = 0xFF555555;
    private static final int SCROLL_TRACK  = 0x40000000;
    private static final int SCROLL_THUMB  = 0xFF8B8B8B;

    private final Screen parent;
    private final Path dumpDir;     // dump output: <gameDir>/aem/
    private final Path sourceDir;   // drop world datapacks (BACAP) here: <gameDir>/aem-datapacks/

    private final List<DatapackInfo> packs = new ArrayList<>();
    private final Set<String> selectedPackIds = new LinkedHashSet<>();
    private boolean packsInitialised;
    private int packScroll;
    private int listLeft, listTop, listInnerW, listInnerH;

    private final Map<String, Checkbox> checkboxes = new LinkedHashMap<>();
    private Button dumpButton;
    private volatile String status = "";
    private volatile boolean running;

    public DumpScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.translatable("gui.aem.dump.title"));
        this.parent = parent;
        Path gameDir = FabricLoader.getInstance().getGameDir();
        this.dumpDir = gameDir.resolve("aem");
        this.sourceDir = gameDir.resolve("aem-datapacks");
    }

    @Override
    protected void init() {
        if (!packsInitialised) {
            try {
                Files.createDirectories(sourceDir);
            } catch (Exception ignored) {
                // best-effort: an unreadable folder just yields an empty pack list
            }
            packs.addAll(DumpDataSource.availableDatapacks(sourceDir));
            packs.forEach(p -> selectedPackIds.add(p.id()));
            packsInitialised = true;
        }

        int left = this.width / 2 - PANEL_WIDTH / 2;

        List<String> files = new ArrayList<>(PackDump.FILES);
        files.add(ENTITIES);
        files.add(PackDump.RAW_DATAPACK);
        int gridRows = (files.size() + GRID_COLS - 1) / GRID_COLS;

        // measure the whole block, then centre it vertically so it always fits the window
        listInnerW = PANEL_WIDTH - 2;
        listInnerH = VISIBLE_PACK_ROWS * PACK_ROW_H;
        int total = TITLE_SPACE + listInnerH + 2 + GAP + gridRows * ROW_GRID + GAP
                + BUTTON_HEIGHT + GAP + BUTTON_HEIGHT;
        int top = Math.max(TITLE_SPACE + 4, (this.height - total) / 2);

        int y = top + TITLE_SPACE;          // leave room for the title above the list
        listLeft = left + 1;
        listTop = y + 1;
        y += listInnerH + 2 + GAP;

        // file-type checkboxes in a grid. The heavy/opt-in targets default off: the raw datapack copy,
        // and entities (spins up a throwaway world).
        checkboxes.clear();
        int colW = PANEL_WIDTH / GRID_COLS;
        for (int i = 0; i < files.size(); i++) {
            int rowY = y + (i / GRID_COLS) * ROW_GRID;
            String file = files.get(i);
            boolean defaultOn = !file.equals(PackDump.RAW_DATAPACK) && !file.equals(ENTITIES);
            addCheckbox(file, left + (i % GRID_COLS) * colW, rowY, defaultOn);
        }
        y += gridRows * ROW_GRID + GAP;

        // primary action
        dumpButton = addRenderableWidget(Button.builder(Component.translatable("gui.aem.dump.run"), b -> dump())
                .bounds(left, y, PANEL_WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + GAP;

        // secondary actions on one line
        int third = (PANEL_WIDTH - 2 * GAP) / 3;
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.dump.open_dump"), b -> openFolder(dumpDir))
                .bounds(left, y, third, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.dump.open_source"), b -> openFolder(sourceDir))
                .bounds(left + third + GAP, y, third, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.connect.back"), b -> onClose())
                .bounds(left + 2 * (third + GAP), y, PANEL_WIDTH - 2 * (third + GAP), BUTTON_HEIGHT).build());
        statusY = y + BUTTON_HEIGHT + GAP;

        clampScroll();

        // Pick up a result handed over by the headless entities dump (we are the screen it returns to).
        if (pendingStatus != null) {
            status = pendingStatus;
            pendingStatus = null;
        }
    }

    private int statusY;

    private void addCheckbox(String file, int x, int y, boolean selected) {
        Checkbox box = Checkbox.builder(Component.literal(file), this.font).pos(x, y).selected(selected).build();
        addRenderableWidget(box);
        checkboxes.put(file, box);
    }

    // -- datapack list ------------------------------------------------------

    private int maxScroll() {
        return Math.max(0, packs.size() - VISIBLE_PACK_ROWS);
    }

    private void clampScroll() {
        packScroll = Math.max(0, Math.min(packScroll, maxScroll()));
    }

    private boolean inList(double mouseX, double mouseY) {
        return mouseX >= listLeft && mouseX < listLeft + listInnerW
                && mouseY >= listTop && mouseY < listTop + listInnerH;
    }

    private void togglePack(String id) {
        if (!selectedPackIds.remove(id)) {
            selectedPackIds.add(id);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && inList(event.x(), event.y()) && !packs.isEmpty()) {
            int index = packScroll + (int) ((event.y() - listTop) / PACK_ROW_H);
            if (index >= 0 && index < packs.size()) {
                togglePack(packs.get(index).id());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inList(mouseX, mouseY) && packs.size() > VISIBLE_PACK_ROWS) {
            packScroll -= (int) Math.signum(scrollY);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // -- actions ------------------------------------------------------------

    /** Create the folder (so it always exists to open) and reveal it in the OS file browser. */
    private void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            Util.getPlatform().openPath(dir);
        } catch (Exception exception) {
            AEM.LOGGER.warn("Could not open folder {}", dir, exception);
            status = Component.translatable("gui.aem.dump.open_failed").getString();
        }
    }

    private void dump() {
        if (running) {
            return;
        }
        Set<String> selected = checkboxes.entrySet().stream()
                .filter(e -> e.getValue().selected())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (selected.isEmpty()) {
            status = Component.translatable("gui.aem.dump.none").getString();
            return;
        }

        // Entities need a live world: hand them to the headless dump, which leaves this screen for a
        // throwaway world and returns once done. The other (world-free) files dump here in parallel.
        boolean entities = selected.remove(ENTITIES);

        if (!selected.isEmpty()) {
            runPackDump(selected);
        }
        if (entities && !HeadlessEntitiesDump.isRunning()) {
            status = Component.translatable("gui.aem.dump.running").getString();
            HeadlessEntitiesDump.request(parent, sourceDir);
        }
    }

    private void runPackDump(Set<String> selected) {
        running = true;
        dumpButton.active = false;
        status = Component.translatable("gui.aem.dump.running").getString();
        Set<String> packIds = Set.copyOf(selectedPackIds);
        new Thread(() -> {
            String result;
            try (CloseableResourceManager rm = DumpDataSource.openServerData(sourceDir, packIds)) {
                result = PackDump.run(rm, dumpDir, selected);
            } catch (Exception exception) {
                AEM.LOGGER.warn("Pack dump failed", exception);
                result = "failed: " + exception;
            }
            String message = result;
            this.minecraft.execute(() -> {
                status = message;
                running = false;
                if (dumpButton != null) {
                    dumpButton.active = true;
                }
            });
        }, "aem-pack-dump").start();
    }

    // -- rendering ----------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title.getString(), this.width / 2, listTop - TITLE_SPACE + 2, 0xFFFFFFFF);

        renderPackList(graphics, mouseX, mouseY);

        if (!status.isEmpty()) {
            graphics.centeredText(this.font, status, this.width / 2, statusY, 0xFFC0C0C0);
        }
    }

    private void renderPackList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = listLeft, top = listTop, w = listInnerW, h = listInnerH;
        // border + background
        graphics.fill(left - 1, top - 1, left + w + 1, top + h + 1, LIST_BORDER);
        graphics.fill(left, top, left + w, top + h, LIST_BG);

        if (packs.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("gui.aem.dump.no_packs").getString(),
                    left + w / 2, top + h / 2 - 4, 0xFF808080);
            return;
        }

        boolean scrollable = packs.size() > VISIBLE_PACK_ROWS;
        int contentW = scrollable ? w - 6 : w;   // leave room for the scrollbar

        graphics.enableScissor(left, top, left + w, top + h);
        int count = Math.min(VISIBLE_PACK_ROWS, packs.size() - packScroll);
        for (int i = 0; i < count; i++) {
            DatapackInfo pack = packs.get(packScroll + i);
            int rowY = top + i * PACK_ROW_H;
            boolean selected = selectedPackIds.contains(pack.id());
            boolean hovered = mouseX >= left && mouseX < left + contentW && mouseY >= rowY && mouseY < rowY + PACK_ROW_H;

            if (selected) {
                graphics.fill(left, rowY, left + contentW, rowY + PACK_ROW_H, ROW_SELECTED);
                graphics.fill(left, rowY, left + contentW, rowY + 1, SELECT_BORDER);
                graphics.fill(left, rowY + PACK_ROW_H - 1, left + contentW, rowY + PACK_ROW_H, SELECT_BORDER);
            } else if (hovered) {
                graphics.fill(left, rowY, left + contentW, rowY + PACK_ROW_H, ROW_HOVER);
            }

            // icon placeholder (a framed square, like a missing pack.png)
            int iconX = left + 3, iconY = rowY + 2, iconS = PACK_ROW_H - 4;
            graphics.fill(iconX, iconY, iconX + iconS, iconY + iconS, ICON_BG);
            graphics.fill(iconX, iconY, iconX + iconS, iconY + 1, ICON_BORDER);
            graphics.fill(iconX, iconY + iconS - 1, iconX + iconS, iconY + iconS, ICON_BORDER);
            graphics.fill(iconX, iconY, iconX + 1, iconY + iconS, ICON_BORDER);
            graphics.fill(iconX + iconS - 1, iconY, iconX + iconS, iconY + iconS, ICON_BORDER);
            graphics.centeredText(this.font, selected ? "✔" : "", iconX + iconS / 2, iconY + iconS / 2 - 4,
                    SELECT_BORDER);

            int textX = iconX + iconS + 5;
            int textW = left + contentW - textX - 3;  // scissor clips any overflow of the title
            graphics.text(this.font, pack.title(), textX, rowY + 5, 0xFFFFFFFF);
            graphics.textWithWordWrap(this.font, pack.description(), textX, rowY + 16, textW, 0xFF9A9A9A);
        }
        graphics.disableScissor();

        if (scrollable) {
            int trackX = left + w - 5;
            graphics.fill(trackX, top, trackX + 5, top + h, SCROLL_TRACK);
            int thumbH = Math.max(16, h * VISIBLE_PACK_ROWS / packs.size());
            int thumbY = top + (h - thumbH) * packScroll / maxScroll();
            graphics.fill(trackX, thumbY, trackX + 5, thumbY + thumbH, SCROLL_THUMB);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
