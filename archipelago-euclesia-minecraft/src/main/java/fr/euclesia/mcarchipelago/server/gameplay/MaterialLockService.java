package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData.ItemGateBehavior;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData.ToolLock;
import fr.euclesia.mcarchipelago.registry.APItemRegistry;
import fr.euclesia.mcarchipelago.registry.APMaterialRegistry;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.ChatFormatting;
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
 * Queried from {@code ItemEntityMixin} (floor pickup), {@code SlotMixin} (crafting/station/container GUI
 * takes) and {@code GiveCommandMixin} ({@code /give}).
 */
public final class MaterialLockService {
    private MaterialLockService() {}

    /**
     * An acquisition route a locked item can travel, matched to an {@code item_gate_behavior} flag. The
     * {@code ItemGateBehavior} option can leave any route open, in which case the lock is not enforced
     * there even for an otherwise-gated item.
     */
    public enum Channel { CRAFTING, STATION, CONTAINER, PICKUP, GIVEN }

    public static boolean isPickupBlocked(ItemStack stack) {
        return blockReason(stack) != null;
    }

    /**
     * Like {@link #blockReason(ItemStack)}, but only reports a block if {@code channel} is gated for
     * this seed (per the {@code ItemGateBehavior} option). Returns {@code null} when the item is
     * unlocked <em>or</em> when the player has chosen to leave this route open.
     */
    public static Component blockReason(ItemStack stack, Channel channel) {
        Component reason = blockReason(stack);
        if (reason == null || !isChannelGated(channel)) {
            return null;
        }
        return reason;
    }

    /** Whether {@code channel} enforces the lock this seed; defaults to gated if slot data is missing. */
    private static boolean isChannelGated(Channel channel) {
        APSlotData slotData = AEM.ARCHIPELAGO.client().state().parsedSlotData();
        ItemGateBehavior behavior = slotData == null ? ItemGateBehavior.DEFAULT : slotData.itemGateBehavior();
        return switch (channel) {
            case CRAFTING -> behavior.crafting();
            case STATION -> behavior.station();
            case CONTAINER -> behavior.container();
            case PICKUP -> behavior.pickup();
            case GIVEN -> behavior.given();
        };
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
                return Component.translatable("message.aem.lock.requires", tool.knowledge())
                        .withStyle(ChatFormatting.RED);
            }
            if (!hasMaterial) {
                return materialMessage(tool.material());
            }
        }
        return null;
    }

    private static Component materialMessage(int requiredTier) {
        return Component.translatable("message.aem.lock.requires_tier",
                APMaterialRegistry.MATERIAL_HANDLING_ITEM, requiredTier).withStyle(ChatFormatting.RED);
    }
}
