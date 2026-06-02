package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.AEMRegistries;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;

/**
 * Gates the *use* of a placed crafting station (enchanting table, brewing stand) behind a Knowledge
 * item: until the player has received e.g. {@code Knowledge: Enchanting}, right-clicking the block
 * does nothing. Covers blocks found in the world (villages, strongholds, …), not just crafted ones —
 * crafting/picking the station up is gated separately via {@link MaterialLockService}. Queried from
 * the block-interaction mixins.
 */
public final class KnowledgeLockService {
    private KnowledgeLockService() {}

    /** True if the placed station {@code blockId} can't be used yet (its Knowledge isn't received). */
    public static boolean isStationUseBlocked(String blockId) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return false;
        }
        AEMRegistries registries = AEM.ARCHIPELAGO.client().registries();
        String knowledge = registries.apMaterials().stationKnowledge(blockId);
        if (knowledge == null) {
            return false;
        }
        return registries.apItems().receivedCount(knowledge) <= 0;
    }
}
