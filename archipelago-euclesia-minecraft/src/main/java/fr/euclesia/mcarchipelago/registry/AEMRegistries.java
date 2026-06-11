package fr.euclesia.mcarchipelago.registry;

import fr.euclesia.mcarchipelago.protocol.registry.APHandlerRegistry;

public final class AEMRegistries {
    private final APHandlerRegistry apHandlers = new APHandlerRegistry();
    private final APItemRegistry apItems = new APItemRegistry();
    private final APLocationRegistry apLocations = new APLocationRegistry();
    private final APGameDataRegistry apGameData = new APGameDataRegistry();
    private final APMaterialRegistry apMaterials = new APMaterialRegistry();
    private final APMobRegistry apMobs = new APMobRegistry();
    private final APStructureRegistry apStructures = new APStructureRegistry();
    private final APTrackerRegistry apTrackers = new APTrackerRegistry();

    public APHandlerRegistry apHandlers() {
        return apHandlers;
    }

    public APItemRegistry apItems() {
        return apItems;
    }

    public APLocationRegistry apLocations() {
        return apLocations;
    }

    public APGameDataRegistry apGameData() {
        return apGameData;
    }

    public APMaterialRegistry apMaterials() {
        return apMaterials;
    }

    public APMobRegistry apMobs() {
        return apMobs;
    }

    public APStructureRegistry apStructures() {
        return apStructures;
    }

    public APTrackerRegistry apTrackers() {
        return apTrackers;
    }
}
