package fr.euclesia.mcarchipelago.registry;

import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData.ToolLock;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Material-handling pickup lock: a gated item (e.g. {@code minecraft:diamond}) cannot be picked up
 * until enough copies of the {@code Progressive Material Handling} item have been received. The slot
 * data maps each item id to the number of copies required (its material tier — copper 2 … netherite
 * 6). Read from the server pickup path, so the backing map is concurrent.
 */
public final class APMaterialRegistry {
    /** AP item whose received count is the player's material-handling tier. */
    public static final String MATERIAL_HANDLING_ITEM = "Progressive Material Handling";

    private final Map<String, Integer> requiredCountByItemId = new ConcurrentHashMap<>();
    private final Map<String, ToolLock> toolLockByItemId = new ConcurrentHashMap<>();

    public void loadSlotData(APSlotData slotData) {
        Map<String, Integer> loaded = new HashMap<>();
        slotData.materialHandlingLocks().forEach((itemId, count) -> loaded.put(itemId, count.intValue()));
        requiredCountByItemId.clear();
        requiredCountByItemId.putAll(loaded);

        toolLockByItemId.clear();
        toolLockByItemId.putAll(slotData.toolLocks());
    }

    /** Copies of {@code Progressive Material Handling} needed to pick this item up; 0 if ungated. */
    public int requiredCount(String itemGameId) {
        return requiredCountByItemId.getOrDefault(itemGameId, 0);
    }

    /** The Knowledge+material gate for a tool/armor item, or {@code null} if it isn't tool-gated. */
    public ToolLock toolLock(String itemGameId) {
        return toolLockByItemId.get(itemGameId);
    }
}
