package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.dump.DumpDataSource;
import fr.euclesia.mcarchipelago.server.command.PackDump;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.CloseableResourceManager;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Content-pack dump panel — tick which files to export and dump them to {@code <gameDir>/aem/} with
 * no world or connection. It reads the datapacks via a standalone {@link DumpDataSource} resource
 * manager, so it works straight from the title menu. The dump runs on a background thread.
 */
public final class DumpScreen extends Screen {

    private static final int PANEL_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 22;

    private final Screen parent;
    private final Map<String, Checkbox> checkboxes = new LinkedHashMap<>();
    private Button dumpButton;
    private volatile String status = "";
    private volatile boolean running;

    public DumpScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.translatable("gui.aem.dump.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int top = this.height / 4;

        checkboxes.clear();
        int y = top;
        for (String file : PackDump.FILES) {
            Checkbox box = Checkbox.builder(Component.literal(file), this.font)
                    .pos(left, y)
                    .selected(true)
                    .build();
            addRenderableWidget(box);
            checkboxes.put(file, box);
            y += ROW;
        }

        dumpButton = addRenderableWidget(Button.builder(Component.translatable("gui.aem.dump.run"), b -> dump())
                .bounds(left, y + 6, PANEL_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.connect.back"), b -> onClose())
                .bounds(left, y + 6 + ROW, PANEL_WIDTH, BUTTON_HEIGHT).build());
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
        running = true;
        dumpButton.active = false;
        status = Component.translatable("gui.aem.dump.running").getString();
        Path outDir = FabricLoader.getInstance().getGameDir().resolve("aem");
        new Thread(() -> {
            String result;
            try (CloseableResourceManager rm = DumpDataSource.openServerData()) {
                result = PackDump.run(rm, outDir, selected);
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int top = this.height / 4;
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, top - 30, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("gui.aem.dump.dir").getString(),
                this.width / 2, top - 16, 0xFFA0A0A0);
        if (!status.isEmpty()) {
            graphics.centeredText(this.font, status, this.width / 2, this.height - 40, 0xFFC0C0C0);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
