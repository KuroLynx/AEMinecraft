package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.client.net.BiomeFinderClient;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opened from the Biome Finder keybind or the inventory-screen button (see
 * {@link fr.euclesia.mcarchipelago.client.AEMClient} / {@link AEMScreenButtons}). Shows a search
 * box over the biomes that can generate in the player's current dimension; picking one asks the
 * server to locate the nearest instance and point the HUD tracker bar at it (see
 * {@link BiomeFinderService}). The dimension scope means nether/end biomes only show up while
 * you're in those dimensions.
 *
 * <p>Both halves of that go through the server ({@link BiomeFinderClient}), so the list arrives a
 * round trip after the screen opens: it draws a loading line until then, and fills itself in from
 * {@link #tick()}.
 */
public final class BiomeFinderScreen extends Screen {
    private static final int LIST_WIDTH = 220;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_SPACING = 22;
    private static final int VISIBLE_ROWS = 6;

    /** A searchable biome: its id plus the display name and a lowercase haystack for filtering. */
    private record Entry(Identifier id, Component name, String haystack) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();

    private EditBox searchField;
    private String lastQuery = "";
    private int scroll;
    /** False until the server's biome list has arrived; the list is empty rather than absent. */
    private boolean loaded;

    public BiomeFinderScreen() {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.translatable("item.aem.biome_finder"));
        // Asked for once per opening — init() also runs on resize, which must not restart the wait.
        BiomeFinderClient.requestList();
    }

    @Override
    protected void init() {
        loadEntries();

        int left = this.width / 2 - LIST_WIDTH / 2;
        int top = this.height / 6;

        searchField = new EditBox(this.font, left, top, LIST_WIDTH, ROW_HEIGHT, Component.translatable("gui.aem.biome_finder.search"));
        searchField.setHint(Component.translatable("gui.aem.biome_finder.search_hint"));
        searchField.setMaxLength(128);
        addRenderableWidget(searchField);
        setInitialFocus(searchField);

        addRenderableWidget(Button.builder(Component.translatable("gui.aem.common.close"), button -> onClose())
                .bounds(left, listTop() + VISIBLE_ROWS * ROW_SPACING + 4, LIST_WIDTH, ROW_HEIGHT)
                .build());

        applyFilter();
        rebuildRows();
    }

    /** Rebuilds {@link #entries} from whatever the server has sent us so far. */
    private void loadEntries() {
        entries.clear();
        List<Identifier> biomes = BiomeFinderClient.biomes();
        loaded = biomes != null;
        if (!loaded) {
            return;
        }
        for (Identifier id : biomes) {
            Component name = Component.translatable(BiomeFinderService.biomeTranslationKey(id));
            String haystack = (name.getString() + " " + id).toLowerCase(Locale.ROOT);
            entries.add(new Entry(id, name, haystack));
        }
    }

    /** Y of the count/scroll hint line, sitting in the gap between the search box and the list. */
    private int hintY() {
        return this.height / 6 + ROW_HEIGHT + 6;
    }

    private int listTop() {
        return this.height / 6 + ROW_HEIGHT + 18;
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

    /** Rebuilds the visible biome buttons for the current filter and scroll position. */
    private void rebuildRows() {
        rowButtons.forEach(this::removeWidget);
        rowButtons.clear();

        int maxScroll = Math.max(0, filtered.size() - VISIBLE_ROWS);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int left = this.width / 2 - LIST_WIDTH / 2;
        int top = listTop();
        int count = Math.min(VISIBLE_ROWS, filtered.size() - scroll);
        for (int i = 0; i < count; i++) {
            Entry entry = filtered.get(scroll + i);
            Button button = Button.builder(entry.name(), b -> pick(entry.id()))
                    .bounds(left, top + i * ROW_SPACING, LIST_WIDTH, ROW_HEIGHT)
                    .build();
            rowButtons.add(addRenderableWidget(button));
        }
    }

    private void pick(Identifier biomeId) {
        BiomeFinderClient.pick(biomeId);
        onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (!loaded && BiomeFinderClient.biomes() != null) {
            loadEntries();
            applyFilter();
            rebuildRows();
        }
        if (!searchField.getValue().equals(lastQuery)) {
            lastQuery = searchField.getValue();
            applyFilter();
            rebuildRows();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (filtered.size() > VISIBLE_ROWS) {
            scroll -= (int) Math.signum(scrollY);
            rebuildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, this.height / 6 - 20, 0xFFFFFFFF);

        if (!loaded) {
            graphics.centeredText(this.font, Component.translatable("gui.aem.biome_finder.loading").getString(),
                    this.width / 2, listTop(), 0xFFA0A0A0);
        } else if (entries.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("gui.aem.biome_finder.empty").getString(),
                    this.width / 2, listTop(), 0xFFFF5555);
        } else if (filtered.size() > VISIBLE_ROWS) {
            String hint = (scroll + VISIBLE_ROWS) + " / " + filtered.size() + " (scroll for more)";
            graphics.centeredText(this.font, hint, this.width / 2, hintY(), 0xFFA0A0A0);
        }
    }
}
