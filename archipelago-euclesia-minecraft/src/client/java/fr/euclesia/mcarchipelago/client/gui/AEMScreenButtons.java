package fr.euclesia.mcarchipelago.client.gui;

import fr.euclesia.mcarchipelago.client.net.BiomeFinderClient;
import fr.euclesia.mcarchipelago.client.render.ConnectionStatusIndicator;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Adds screen buttons the same way throughout: a {@link ScreenEvents#AFTER_INIT} hook rather than
 * a mixin, since Fabric already exposes everything needed (the screen, its size, and a place to
 * drop extra widgets) without touching vanilla's own layout code.
 *
 * <p>Two buttons live here:
 * <ul>
 *   <li>The Archipelago icon button (the advancement-tab logo) on the (in-game) options screen,
 *       styled like the vanilla language / accessibility buttons. Opens the
 *       {@link ArchipelagoOptionScreen} options panel. Not added to the title screen — there is
 *       no connected session there to show options for.</li>
 *   <li>The Biome Finder button on the survival inventory screen, recipe-book-toggle-style: no
 *       physical item or slot behind it, just a click (or the {@code key.aem.biome_finder}
 *       keybind polled in {@link fr.euclesia.mcarchipelago.client.AEMClient}) that opens
 *       {@link BiomeFinderScreen} directly. Hidden until the Biome Finder Archipelago item is
 *       owned, same gate the keybind uses.</li>
 * </ul>
 */
public final class AEMScreenButtons {

    /** GUI sprite at {@code assets/aem/textures/gui/sprites/archipelago/logo.png}. */
    private static final Identifier CONNECT_ICON = Identifier.fromNamespaceAndPath("aem", "archipelago/logo");
    private static final Component CONNECT_LABEL = Component.translatable("gui.aem.connect.open");
    private static final int SIZE = 20;
    private static final int MARGIN = 6;

    private static final Component BIOME_FINDER_LABEL = Component.translatable("gui.aem.biome_finder.open");
    private static final int BIOME_FINDER_WIDTH = 90;
    private static final int BIOME_FINDER_HEIGHT = 20;

    private AEMScreenButtons() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Draw the connection sphere over every screen, so it stays visible in menus where the
            // in-game HUD does not render.
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
                    ConnectionStatusIndicator.draw(graphics));

            // Only the (in-game) options screen — the main menu has no connected session to show
            // options for; connecting happens from the world-creation screen instead.
            if (screen instanceof OptionsScreen) {
                SpriteIconButton button = SpriteIconButton.builder(CONNECT_LABEL,
                                ignored -> client.setScreen(new ArchipelagoOptionScreen(screen)), true)
                        .sprite(CONNECT_ICON, 16, 16)
                        .size(SIZE, SIZE)
                        .build();
                button.setX(MARGIN);
                button.setY(height - SIZE - MARGIN);
                Screens.getWidgets(screen).add(button);
            }

            if (screen instanceof InventoryScreen && BiomeFinderClient.owns()) {
                Button button = Button.builder(BIOME_FINDER_LABEL,
                                ignored -> client.setScreen(new BiomeFinderScreen()))
                        .bounds(MARGIN, height - BIOME_FINDER_HEIGHT - MARGIN,
                                BIOME_FINDER_WIDTH, BIOME_FINDER_HEIGHT)
                        .build();
                Screens.getWidgets(screen).add(button);
            }
        });
    }
}
