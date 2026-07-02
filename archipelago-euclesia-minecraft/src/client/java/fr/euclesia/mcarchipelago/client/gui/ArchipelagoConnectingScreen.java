package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.CompatibilityService;
import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Pre-flight "Connecting to Archipelago…" step shown during world load, before the integrated server
 * starts (see {@code WorldOpenFlowsMixin}). On a live session it resumes the load — which then
 * proceeds to spawn-area generation as normal — so nothing generates until we are connected. On
 * failure it shows the reason and a Back button that aborts the load and returns to the title.
 */
public final class ArchipelagoConnectingScreen extends Screen {

    private final APWorldConnection connection;
    private final Runnable onConnected;
    private final Runnable onAbort;
    private boolean started;
    private boolean resumed;
    private boolean compatChecked;
    private Component compatError;
    private Button actionButton;

    public ArchipelagoConnectingScreen(APWorldConnection connection, Runnable onConnected, Runnable onAbort) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font,
                Component.translatable("gui.aem.connect.connecting"));
        this.connection = connection;
        this.onConnected = onConnected;
        this.onAbort = onAbort;
    }

    @Override
    protected void init() {
        // Start the attempt exactly once per screen (init() also fires on resize). A fresh connect()
        // resets the controller status, so a stale CONNECTED left over from a previous world can't make
        // us resume the load without actually reconnecting.
        if (!started) {
            started = true;
            APConnectController.INSTANCE.connect(
                    connection.address, connection.port, connection.slot, connection.password);
        }

        int buttonWidth = 200;
        actionButton = addRenderableWidget(Button.builder(Component.translatable("gui.aem.connect.cancel"), b -> onAbort.run())
                .bounds(this.width / 2 - buttonWidth / 2, this.height / 2 + 30, buttonWidth, 20)
                .build());
    }

    @Override
    public void tick() {
        super.tick();
        if (resumed) {
            return;
        }
        APConnectController.Status status = APConnectController.INSTANCE.status();
        if (status == APConnectController.Status.CONNECTED) {
            // Gate on slot-data compatibility before entering the world: a world whose schema this
            // mod can't read is refused here (like a failed connect) rather than loaded broken.
            if (!compatChecked) {
                compatChecked = true;
                CompatibilityService.Result compat = CompatibilityService.check(
                        AEM.ARCHIPELAGO.client().state().parsedSlotData().slotDataVersion());
                if (compat.blocking()) {
                    compatError = compat.message();
                    AEM.ARCHIPELAGO.client().close();
                    if (actionButton != null) {
                        actionButton.setMessage(Component.translatable("gui.aem.connect.back"));
                    }
                }
            }
            if (compatError == null) {
                resumed = true;
                // Resume the world load; openWorldDoLoad replaces this screen with the loading screens.
                onConnected.run();
            }
        } else if (status == APConnectController.Status.FAILED && actionButton != null) {
            actionButton.setMessage(Component.translatable("gui.aem.connect.back"));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // A compatibility block presents like a connection failure, but with its own header/detail.
        boolean incompatible = compatError != null;
        boolean failed = incompatible
                || APConnectController.INSTANCE.status() == APConnectController.Status.FAILED;
        int centerX = this.width / 2;
        int top = this.height / 2 - 30;

        Component headerComponent = failed
                ? (incompatible
                        ? Component.translatable("gui.aem.connect.incompatible_header")
                        : Component.translatable("gui.aem.connect.failed_header"))
                : Component.translatable("gui.aem.connect.connecting");
        graphics.centeredText(this.font, headerComponent.getString(), centerX, top, failed ? 0xFFFF5555 : 0xFFFFFFFF);

        String target = Component.translatable("gui.aem.connect.connecting.target",
                connection.address + ":" + connection.port, connection.slot).getString();
        graphics.centeredText(this.font, target, centerX, top + 14, 0xFFA0A0A0);

        if (failed) {
            String detail = incompatible ? compatError.getString() : APConnectController.INSTANCE.message();
            if (!detail.isEmpty()) {
                graphics.centeredText(this.font, detail, centerX, top + 28, 0xFFFF5555);
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        onAbort.run();
    }
}
