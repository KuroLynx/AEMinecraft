package fr.euclesia.mcarchipelago.client;

import fr.euclesia.mcarchipelago.client.connect.APConnectController;
import fr.euclesia.mcarchipelago.client.gui.AEMScreenButtons;
import fr.euclesia.mcarchipelago.client.logic.DataLogicProvider;
import fr.euclesia.mcarchipelago.client.logic.LogicProviders;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

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
	}
}
