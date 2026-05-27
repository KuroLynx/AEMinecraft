package fr.euclesia.mcarchipelago;

import fr.euclesia.mcarchipelago.engine.ap.ArchipelagoService;
import fr.euclesia.mcarchipelago.server.AEMServerBridge;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AEM implements ModInitializer {
	public static final String MOD_ID = "archipelago-euclesia-minecraft";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final ArchipelagoService ARCHIPELAGO = ArchipelagoService.createDefault();

	@Override
	public void onInitialize() {
		AEMServerBridge.register();
		LOGGER.info("Archipelago Euclesia Minecraft initialized");
	}
}
