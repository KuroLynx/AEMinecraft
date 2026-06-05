package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.client.connect.APConnectConfig;
import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Main-menu / options screen for entering Archipelago connection details (address, port, slot name,
 * password) and connecting. Works from the title screen because the link is a plain WebSocket; the
 * session persists into whatever world is loaded next.
 */
public final class ArchipelagoConnectScreen extends Screen {

    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_SPACING = 38;

    private final Screen parent;

    private EditBox addressField;
    private EditBox portField;
    private EditBox slotField;
    private EditBox passwordField;

    /** Set when the user pressed Connect, so we only auto-close on a connection we initiated. */
    private boolean connectRequested;

    public ArchipelagoConnectScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.translatable("gui.aem.connect.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        APConnectConfig config = APConnectConfig.get();
        APConnectController.INSTANCE.syncFromSession();

        int left = this.width / 2 - FIELD_WIDTH / 2;
        int top = this.height / 4;

        addressField = addField(left, top, "archipelago.gg", config.address);
        portField = addField(left, top + ROW_SPACING, "38281", config.port);
        slotField = addField(left, top + ROW_SPACING * 2, "Slot name", config.slot);
        passwordField = addField(left, top + ROW_SPACING * 3, "(optional)", config.password);

        int buttonsY = top + ROW_SPACING * 4 + 8;
        int buttonWidth = (FIELD_WIDTH - 8) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.connect.connect"), button -> onConnect())
                .bounds(left, buttonsY, buttonWidth, FIELD_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.connect.back"), button -> onClose())
                .bounds(left + FIELD_WIDTH - buttonWidth, buttonsY, buttonWidth, FIELD_HEIGHT)
                .build());

        setInitialFocus(slotField.getValue().isBlank() ? slotField : addressField);
    }

    private EditBox addField(int x, int y, String hint, String value) {
        EditBox field = new EditBox(this.font, x, y + 12, FIELD_WIDTH, FIELD_HEIGHT, Component.literal(hint));
        field.setMaxLength(256);
        field.setHint(Component.literal(hint));
        field.setValue(value);
        return addRenderableWidget(field);
    }

    private void onConnect() {
        APConnectConfig config = APConnectConfig.get();
        config.address = addressField.getValue();
        config.port = portField.getValue();
        config.slot = slotField.getValue();
        config.password = passwordField.getValue();
        config.save();

        connectRequested = true;
        APConnectController.INSTANCE.connect(config.address, config.port, config.slot, config.password);
    }

    @Override
    public void tick() {
        super.tick();
        // Close the screen once the connection we requested succeeds.
        if (connectRequested && APConnectController.INSTANCE.status() == APConnectController.Status.CONNECTED) {
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int left = this.width / 2 - FIELD_WIDTH / 2;
        int top = this.height / 4;

        graphics.centeredText(this.font, this.title.getString(), this.width / 2, top - 30, 0xFFFFFFFF);
        graphics.text(this.font, Component.translatable("gui.aem.field.address").getString(), left, top, 0xFFA0A0A0);
        graphics.text(this.font, Component.translatable("gui.aem.field.port").getString(), left, top + ROW_SPACING, 0xFFA0A0A0);
        graphics.text(this.font, Component.translatable("gui.aem.field.slot").getString(), left, top + ROW_SPACING * 2, 0xFFA0A0A0);
        graphics.text(this.font, Component.translatable("gui.aem.field.password").getString(), left, top + ROW_SPACING * 3, 0xFFA0A0A0);

        String message = APConnectController.INSTANCE.message();
        if (!message.isEmpty()) {
            graphics.centeredText(this.font, message, this.width / 2, top + ROW_SPACING * 4 + 36, statusColor());
        }
    }

    private int statusColor() {
        return switch (APConnectController.INSTANCE.status()) {
            case CONNECTED -> 0xFF55FF55;
            case FAILED -> 0xFFFF5555;
            default -> 0xFFFFFF55;
        };
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
