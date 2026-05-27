package fr.euclesia.mcarchipelago.registry;

import fr.euclesia.mcarchipelago.protocol.registry.APHandlerRegistry;

public final class AEMRegistries {
    private final APHandlerRegistry apHandlers = new APHandlerRegistry();
    private final APItemRegistry apItems = new APItemRegistry();
    private final APLocationRegistry apLocations = new APLocationRegistry();
    private final APMobRegistry apMobs = new APMobRegistry();

    public APHandlerRegistry apHandlers() {
        return apHandlers;
    }

    public APItemRegistry apItems() {
        return apItems;
    }

    public APLocationRegistry apLocations() {
        return apLocations;
    }

    public APMobRegistry apMobs() {
        return apMobs;
    }
}
