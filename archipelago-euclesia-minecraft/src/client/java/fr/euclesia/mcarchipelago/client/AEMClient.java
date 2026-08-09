package fr.euclesia.mcarchipelago.client;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import fr.euclesia.mcarchipelago.client.connect.WorldLoadResume;
import fr.euclesia.mcarchipelago.client.dump.HeadlessWorldDump;
import fr.euclesia.mcarchipelago.client.finder.StructureFinderBarHud;
import fr.euclesia.mcarchipelago.client.gui.AEMScreenButtons;
import fr.euclesia.mcarchipelago.client.gui.BiomeFinderScreen;
import fr.euclesia.mcarchipelago.client.logic.DataLogicProvider;
import fr.euclesia.mcarchipelago.client.net.APStateSyncClient;
import fr.euclesia.mcarchipelago.client.net.BiomeFinderClient;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.client.render.ConnectionStatusHud;
import fr.euclesia.mcarchipelago.content.BiomeFinderItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

@Environment(EnvType.CLIENT)
public class AEMClient implements ClientModInitializer {
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

		// Resume a world load deferred by the pre-flight connect (see MinecraftWorldLoadMixin), run
		// here so doWorldLoad executes outside any screen-tick bracket. Also drive the headless
		// entities-dump teardown (leave + delete the temp world once it has dumped).
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			WorldLoadResume.runPending();
			HeadlessWorldDump.clientTick(client);
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

		// Right-clicking the Biome Finder compass opens the biome pick screen. Two callbacks: one for
		// using it on a block, one for using it in the air. Returning SUCCESS in BOTH the client and
		// server passes cancels the default interaction — in particular it stops the finder from being
		// bound to a lodestone (vanilla compass behaviour we don't want). The screen only opens on the
		// client; the isClientSide guard keeps Minecraft/screen references off the server thread.
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				BiomeFinderItem.isFinder(player.getItemInHand(hand))
						? openFinder(level)
						: InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				BiomeFinderItem.isFinder(player.getItemInHand(hand))
						? openFinder(level)
						: InteractionResult.PASS);
	}

	private static InteractionResult openFinder(net.minecraft.world.level.Level level) {
		if (level.isClientSide()) {
			Minecraft.getInstance().setScreen(new BiomeFinderScreen());
		}
		return InteractionResult.SUCCESS;
	}
}
