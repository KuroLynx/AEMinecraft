package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.AEMRegistries;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;

/**
 * Gates inter-dimension portal travel behind the Archipelago {@code Dimension Unlock} items: a
 * player can't pass into the Nether/End (or back to the Overworld, for a Nether start) until the
 * destination's unlock item is received. The start dimension is always free — it has no unlock item
 * in the pool, so it never appears in the slot map and is therefore never blocked. Queried from
 * {@code EntityMixin#canTeleport}.
 */
public final class DimensionLockService {
    private DimensionLockService() {}

    /** True if entering {@code dimensionId} (e.g. {@code minecraft:the_nether}) is still locked. */
    public static boolean isDimensionEntryBlocked(String dimensionId) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return false;
        }
        AEMRegistries registries = AEM.ARCHIPELAGO.client().registries();
        String unlockItem = registries.apMaterials().dimensionUnlockItem(dimensionId);
        if (unlockItem == null) {
            return false; // not gated (the free start dimension, or gating disabled)
        }
        return registries.apItems().receivedCount(unlockItem) <= 0;
    }
}
