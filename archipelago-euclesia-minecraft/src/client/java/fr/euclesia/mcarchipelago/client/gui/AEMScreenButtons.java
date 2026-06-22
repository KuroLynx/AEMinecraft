package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.render.ConnectionStatusIndicator;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Adds an Archipelago icon button (the advancement-tab logo) to the (in-game) options screen,
 * styled like the vanilla language / accessibility buttons. Clicking it opens the
 * {@link ArchipelagoConnectScreen} options panel. Not added to the title screen — there is no
 * connected session there to show options for.
 */
public final class AEMScreenButtons {

    /** GUI sprite at {@code assets/aem/textures/gui/sprites/archipelago/logo.png}. */
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("aem", "archipelago/logo");
    private static final Component LABEL = Component.translatable("gui.aem.connect.open");
    private static final int SIZE = 20;
    private static final int MARGIN = 6;

    private AEMScreenButtons() {}

    private static final Component CREATE_WORLD_LABEL = Component.translatable("selectWorld.create");

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Draw the connection sphere over every screen, so it stays visible in menus where the
            // in-game HUD does not render.
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
                    ConnectionStatusIndicator.draw(graphics));

            // One world only: once a world exists, hide "Create New World" so no second one is made.
            if (screen instanceof SelectWorldScreen && aWorldAlreadyExists()) {
                hideCreateWorldButton(screen);
            }

            // Only the (in-game) options screen — the main menu has no connected session to show
            // options for; connecting happens from the world-creation screen instead.
            if (!(screen instanceof OptionsScreen)) {
                return;
            }

            SpriteIconButton button = SpriteIconButton.builder(LABEL,
                            ignored -> client.setScreen(new ArchipelagoConnectScreen(screen)), true)
                    .sprite(ICON, 16, 16)
                    .size(SIZE, SIZE)
                    .build();
            button.setX(MARGIN);
            button.setY(height - SIZE - MARGIN);
            Screens.getWidgets(screen).add(button);
        });
    }

    private static boolean aWorldAlreadyExists() {
        try {
            return !Minecraft.getInstance().getLevelSource().findLevelCandidates().isEmpty();
        } catch (Exception exception) {
            AEM.LOGGER.warn("Could not list worlds to enforce single-world", exception);
            return false;
        }
    }

    /** Hides the create-world button (matched by its label) on the world-selection screen. */
    private static void hideCreateWorldButton(Screen screen) {
        String createLabel = CREATE_WORLD_LABEL.getString();
        for (Object element : Screens.getWidgets(screen)) {
            if (element instanceof AbstractWidget widget
                    && createLabel.equals(widget.getMessage().getString())) {
                widget.visible = false;
                widget.active = false;
            }
        }
    }
}
