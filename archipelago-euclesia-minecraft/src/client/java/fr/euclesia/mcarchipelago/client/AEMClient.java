package fr.euclesia.mcarchipelago.client;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import fr.euclesia.mcarchipelago.client.gui.AEMScreenButtons;
import fr.euclesia.mcarchipelago.client.logic.DataLogicProvider;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import fr.euclesia.mcarchipelago.client.render.ConnectionStatusHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

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
	}
}
