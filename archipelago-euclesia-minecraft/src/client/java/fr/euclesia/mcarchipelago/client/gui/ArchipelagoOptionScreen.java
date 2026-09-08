package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.APSessionState;
import fr.euclesia.mcarchipelago.archipelago.ChatFilterPreference;
import fr.euclesia.mcarchipelago.archipelago.DeathLinkPreference;
import fr.euclesia.mcarchipelago.client.connect.APConnectConfig;
import fr.euclesia.mcarchipelago.client.net.ChatFilterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Archipelago options / status panel, opened from the title and options screens. Connection
 * credentials are entered in the world-creation screen ({@link ArchipelagoCreateTab}), so this
 * screen no longer connects — once a session is live it shows the slot's details and the options
 * that can change at runtime: a DeathLink on/off toggle, a chat-filter on/off toggle (also bound
 * to the {@code key.aem.chat_filter} keybind, default H), and a Resync button (re-pull received
 * items).
 */
public final class ArchipelagoOptionScreen extends Screen {

    private static final int PANEL_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 24;

    private final Screen parent;
    private Button deathLinkButton;
    private Button chatFilterButton;

    public ArchipelagoOptionScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.translatable("gui.aem.options.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int buttonsTop = this.height / 4 + 60;

        // Runtime options only exist for a live session; otherwise just an explanatory line + Back.
        if (isConnected()) {
            deathLinkButton = addRenderableWidget(Button.builder(deathLinkLabel(), button -> toggleDeathLink())
                    .bounds(left, buttonsTop, PANEL_WIDTH, BUTTON_HEIGHT)
                    .build());
            chatFilterButton = addRenderableWidget(Button.builder(chatFilterLabel(), button -> toggleChatFilter())
                    .bounds(left, buttonsTop + ROW, PANEL_WIDTH, BUTTON_HEIGHT)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("gui.aem.options.resync"), button -> resync())
                    .bounds(left, buttonsTop + ROW * 2, PANEL_WIDTH, BUTTON_HEIGHT)
                    .build());
        }

        // Dump the data-driven content-pack files (works with no session — even from the title menu).
        addRenderableWidget(Button.builder(Component.translatable("gui.aem.dump.open"),
                        button -> minecraft.setScreen(new DumpScreen(this)))
                .bounds(left, buttonsTop + ROW * 3, PANEL_WIDTH, BUTTON_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.aem.connect.back"), button -> onClose())
                .bounds(left, buttonsTop + ROW * 4 + 8, PANEL_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    private boolean isConnected() {
        return AEM.ARCHIPELAGO.client().state().isConnected();
    }

    private Component deathLinkLabel() {
        Component state = Component.translatable(
                DeathLinkPreference.enabled() ? "gui.aem.toggle.on" : "gui.aem.toggle.off");
        return Component.translatable("gui.aem.connect.deathlink", state);
    }

    private void toggleDeathLink() {
        DeathLinkPreference.setEnabled(!DeathLinkPreference.enabled());
        deathLinkButton.setMessage(deathLinkLabel());
    }

    private Component chatFilterLabel() {
        Component state = Component.translatable(
                ChatFilterPreference.enabled() ? "gui.aem.toggle.on" : "gui.aem.toggle.off");
        return Component.translatable("gui.aem.connect.chatfilter", state);
    }

    /**
     * Unlike DeathLink's direct field flip, this sends a request and waits for the server's
     * broadcast to actually change {@link ChatFilterPreference} — it is the same request path the
     * keybind uses, so the label update here just re-reads whatever the sync last set once it
     * arrives (see {@code APStateSyncClient}), not this click.
     */
    private void toggleChatFilter() {
        ChatFilterClient.requestToggle();
    }

    private void resync() {
        if (isConnected()) {
            AEM.ARCHIPELAGO.gateway().resync();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int top = this.height / 4;
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, top - 30, 0xFFFFFFFF);

        if (isConnected()) {
            drawSlotInfo(graphics, top);
            // The chat-filter label can change from a toggle sent elsewhere (the keybind, or
            // another player using theirs, since it is a shared run-wide setting) while this
            // screen is open, so it is kept in sync every frame rather than only on click.
            if (chatFilterButton != null) {
                chatFilterButton.setMessage(chatFilterLabel());
            }
        } else {
            graphics.centeredText(this.font, Component.translatable("gui.aem.options.disconnected").getString(),
                    this.width / 2, top, 0xFFA0A0A0);
        }
    }

    /** Draws the connected slot's details (name/number/team and server), centred above the buttons. */
    private void drawSlotInfo(GuiGraphicsExtractor graphics, int top) {
        APSessionState state = AEM.ARCHIPELAGO.client().state();
        APConnectConfig config = APConnectConfig.get();

        String name = state.playerName(state.slot());
        if (name == null || name.isBlank()) {
            name = config.slot;
        }
        graphics.centeredText(this.font,
                Component.translatable("gui.aem.connect.info.slot", name, state.slot(), state.team()).getString(),
                this.width / 2, top, 0xFFC0C0C0);
        graphics.centeredText(this.font,
                Component.translatable("gui.aem.connect.info.server", config.address, config.port).getString(),
                this.width / 2, top + 12, 0xFFC0C0C0);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
