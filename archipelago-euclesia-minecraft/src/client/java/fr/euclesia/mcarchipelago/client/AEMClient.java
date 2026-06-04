package fr.euclesia.mcarchipelago.client;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import fr.euclesia.mcarchipelago.client.finder.StructureFinderBarHud;
import fr.euclesia.mcarchipelago.client.gui.AEMScreenButtons;
import fr.euclesia.mcarchipelago.client.gui.BiomeFinderScreen;
import fr.euclesia.mcarchipelago.client.logic.DataLogicProvider;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.client.render.ConnectionStatusHud;
import fr.euclesia.mcarchipelago.content.BiomeFinderItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
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

		// Listen for Archipelago connect results and add the connect button to the menus.
		APConnectController.init();
		AEMScreenButtons.register();

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
