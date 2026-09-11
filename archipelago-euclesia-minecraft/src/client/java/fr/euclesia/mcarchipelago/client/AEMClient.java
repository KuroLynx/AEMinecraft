package fr.euclesia.mcarchipelago.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import fr.euclesia.mcarchipelago.client.connect.WorldLoadResume;
import fr.euclesia.mcarchipelago.client.dump.HeadlessWorldDump;
import fr.euclesia.mcarchipelago.client.finder.BiomeFinderTrackerHud;
import fr.euclesia.mcarchipelago.client.finder.StructureFinderBarHud;
import fr.euclesia.mcarchipelago.client.gui.AEMScreenButtons;
import fr.euclesia.mcarchipelago.client.gui.BiomeFinderScreen;
import fr.euclesia.mcarchipelago.client.logic.DataLogicProvider;
import fr.euclesia.mcarchipelago.client.net.APStateSyncClient;
import fr.euclesia.mcarchipelago.client.net.BiomeFinderClient;
import fr.euclesia.mcarchipelago.client.net.ChatFilterClient;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.client.render.ConnectionStatusHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import fr.euclesia.mcarchipelago.client.render.AdvancementRenderHooks;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public class AEMClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(AEM.MOD_ID, "keys"));

	/** Toggles the chat filter (hide multiworld noise not connected to this slot); default H. */
	private static final KeyMapping TOGGLE_CHAT_FILTER = new KeyMapping(
			"key.aem.chat_filter", InputConstants.Type.KEYSYM, InputConstants.KEY_H, CATEGORY);

	/** Opens the Biome Finder search screen; default key B, rebindable in Controls. */
	private static final KeyMapping OPEN_BIOME_FINDER = new KeyMapping(
			"key.aem.biome_finder", InputConstants.Type.KEYSYM, InputConstants.KEY_B, CATEGORY);

	@Override
	public void onInitializeClient() {
		// Install the real reachability source for the advancement-screen overlay,
		// replacing the Phase-1 StubLogicProvider.
		LogicProviders.set(new DataLogicProvider());
		// Receives the server's session on a dedicated server, where this client has none of its own.
		APStateSyncClient.register();
		// The Biome Finder's pick screen: the biome list and the pick both travel over the wire.
		BiomeFinderClient.register();

		// Listen for Archipelago connect results and add the connect button to the menus.
		APConnectController.init();
		AEMScreenButtons.register();

		KeyMappingHelper.registerKeyMapping(TOGGLE_CHAT_FILTER);
		KeyMappingHelper.registerKeyMapping(OPEN_BIOME_FINDER);

		// Resume a world load deferred by the pre-flight connect (see MinecraftWorldLoadMixin), run
		// here so doWorldLoad executes outside any screen-tick bracket. Also drive the headless
		// entities-dump teardown (leave + delete the temp world once it has dumped), and poll the
		// chat-filter and Biome Finder keybinds (the latter gated on ownership, same as the
		// inventory-screen button).
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			WorldLoadResume.runPending();
			HeadlessWorldDump.clientTick(client);
			while (TOGGLE_CHAT_FILTER.consumeClick()) {
				ChatFilterClient.requestToggle();
			}
			while (OPEN_BIOME_FINDER.consumeClick()) {
				if (client.screen == null && BiomeFinderClient.owns()) {
					client.setScreen(new BiomeFinderScreen());
				}
			}
		});

		// When the headless entities-dump temp world has started, write entities.json off it.
		ServerLifecycleEvents.SERVER_STARTED.register(HeadlessWorldDump::onServerStarted);

		// Top-left HUD sphere: green when connected to Archipelago, red when not.
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(AEM.MOD_ID, "connection_status"),
				new ConnectionStatusHud());

		// Structure Finder tier 1: a custom locator bar showing each target's representative item icon.
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(AEM.MOD_ID, "structure_finder_bar"),
				new StructureFinderBarHud());

		// Biome Finder: a single-icon locator bar pointing at the last biome located.
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(AEM.MOD_ID, "biome_finder_tracker"),
				new BiomeFinderTrackerHud());

		// Advancement frames are repainted from their own artwork (AdvancementRenderHooks), so a
		// resource reload — a pack switched on, F3+T — has to throw those repaints away: the sprite
		// they were built from may look nothing like it did.
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
				Identifier.fromNamespaceAndPath(AEM.MOD_ID, "repainted_sprites"),
				new SimpleReloadListener<Void>() {
					@Override
					protected Void prepare(PreparableReloadListener.SharedState state) {
						return null;
					}

					@Override
					protected void apply(Void prepared, PreparableReloadListener.SharedState state) {
						AdvancementRenderHooks.clearRepaints();
					}
				});
	}
}
