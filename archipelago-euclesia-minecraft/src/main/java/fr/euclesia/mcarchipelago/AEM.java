package fr.euclesia.mcarchipelago;

import fr.euclesia.mcarchipelago.content.AEMEffects;
import fr.euclesia.mcarchipelago.content.BiomeFinderItem;
import fr.euclesia.mcarchipelago.engine.ap.ArchipelagoService;
import fr.euclesia.mcarchipelago.server.AEMServerBridge;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AEM implements ModInitializer {
	public static final String MOD_ID = "aem";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final ArchipelagoService ARCHIPELAGO = ArchipelagoService.createDefault();

	@Override
	public void onInitialize() {
		// Register custom data components (e.g. the Biome Finder marker) and status effects (trap
		// items) before registries freeze.
		BiomeFinderItem.register();
		AEMEffects.register();
		AEMServerBridge.register();
		LOGGER.info("Archipelago Euclesia Minecraft initialized");
	}
}
