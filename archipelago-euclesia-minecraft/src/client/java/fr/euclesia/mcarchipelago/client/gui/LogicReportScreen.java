package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.client.report.LogicReportMode;
import fr.euclesia.mcarchipelago.client.utils.MCClient;
import fr.euclesia.mcarchipelago.net.LogicReportNet;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * What is wrong with one advancement: its logic is wrong, or it should not be accessible yet — plus an
 * optional note. Sends the report and returns to the advancement screen it came from.
 */
public final class LogicReportScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 24;

    private final Screen parent;
    private final Identifier advancementId;
    private final String advancementTitle;
    private EditBox note;

    public LogicReportScreen(Screen parent, Identifier advancementId) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.translatable("gui.aem.logic_report.title"));
        this.parent = parent;
        this.advancementId = advancementId;
        this.advancementTitle = titleOf(advancementId);
    }

    private static String titleOf(Identifier id) {
        var player = Minecraft.getInstance().player;
        AdvancementHolder holder = player == null ? null : player.connection.getAdvancements().get(id);
        if (holder == null) {
            return id.toString();
        }
        // 26.3 made DisplayInfo a record: getTitle() became title().
        //? if >=26.3 {
        /*return holder.value().display().map(display -> display.title().getString()).orElse(id.toString());
        *///?} else {
        return holder.value().display().map(display -> display.getTitle().getString()).orElse(id.toString());
        //?}
    }

    @Override
    protected void init() {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int top = this.height / 4 + 30;

        note = new EditBox(this.font, left, top, PANEL_WIDTH, BUTTON_HEIGHT, Component.translatable("gui.aem.logic_report.note"));
        note.setMaxLength(LogicReportNet.MAX_NOTE_LENGTH);
        note.setHint(Component.translatable("gui.aem.logic_report.note"));
        addRenderableWidget(note);

        addRenderableWidget(Button.builder(Component.translatable("gui.aem.logic_report.wrong_logic"),
                        button -> submit("wrong_logic"))
                .bounds(left, top + ROW + 6, PANEL_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.logic_report.should_not_be_accessible"),
                        button -> submit("should_not_be_accessible"))
                .bounds(left, top + ROW * 2 + 6, PANEL_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.connect.back"), button -> onClose())
                .bounds(left, top + ROW * 3 + 14, PANEL_WIDTH, BUTTON_HEIGHT).build());
    }

    private void submit(String kind) {
        LogicReportMode.send(advancementId, kind, note.getValue());
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int top = this.height / 4;
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, top - 10, 0xFFFFFFFF);
        graphics.centeredText(this.font, advancementTitle, this.width / 2, top + 4, 0xFFFFFF55);
        graphics.centeredText(this.font,
                Component.translatable("gui.aem.logic_report.state",
                        LogicProviders.stateFor(advancementId).name()).getString(),
                this.width / 2, top + 16, 0xFFA0A0A0);
    }

    /** Back to the advancement screen, rebuilt. */
    @Override
    public void onClose() {
        if (parent != null) {
            AEMScreenButtons.reopen(parent);
        } else {
            MCClient.setScreen(null);
        }
    }
}
