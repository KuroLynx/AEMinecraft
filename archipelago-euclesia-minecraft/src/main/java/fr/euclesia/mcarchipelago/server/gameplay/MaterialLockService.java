package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData.ToolLock;
import fr.euclesia.mcarchipelago.registry.APItemRegistry;
import fr.euclesia.mcarchipelago.registry.APMaterialRegistry;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Decides whether an item is too "advanced" to be picked up yet. Two gates, both keyed off received
 * Archipelago items:
 * <ul>
 *   <li>raw materials (diamond, iron, …) stay locked until enough copies of
 *       {@code Progressive Material Handling} have been received (the item's material tier);</li>
 *   <li>tools/armor (e.g. a diamond sword) need BOTH the relevant Knowledge item AND that material
 *       tier.</li>
 * </ul>
 * Queried from {@code ItemEntityMixin} (floor pickup) and {@code SlotMixin} (container/crafting takes).
 */
public final class MaterialLockService {
    private MaterialLockService() {}

    public static boolean isPickupBlocked(ItemStack stack) {
        return blockReason(stack) != null;
    }

    /**
     * The reason {@code stack} can't be picked up yet (a red chat line for {@link LockFeedback}), or
     * {@code null} if it's allowed. Same gates as {@link #isPickupBlocked}.
     */
    public static Component blockReason(ItemStack stack) {
        if (stack.isEmpty() || !AEMServerRuntime.isArchipelagoReady()) {
            return null;
        }
        APMaterialRegistry materials = AEM.ARCHIPELAGO.client().registries().apMaterials();
        APItemRegistry items = AEM.ARCHIPELAGO.client().registries().apItems();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int materialTier = items.receivedCount(APMaterialRegistry.MATERIAL_HANDLING_ITEM);

        // Raw-material gate: need enough Progressive Material Handling for this item's tier.
        int required = materials.requiredCount(itemId);
        if (required > 0 && materialTier < required) {
            return materialMessage(required);
        }

        // Tool/armor gate: need the Knowledge item AND the material tier.
        ToolLock tool = materials.toolLock(itemId);
        if (tool != null) {
            boolean hasKnowledge = items.receivedCount(tool.knowledge()) > 0;
            boolean hasMaterial = materialTier >= tool.material();
            if (!hasKnowledge) {
                return Component.literal("§cRequires " + tool.knowledge());
            }
            if (!hasMaterial) {
                return materialMessage(tool.material());
            }
        }
        return null;
    }

    private static Component materialMessage(int requiredTier) {
        return Component.literal("§cRequires " + APMaterialRegistry.MATERIAL_HANDLING_ITEM
                + " (" + requiredTier + ")");
    }
}
