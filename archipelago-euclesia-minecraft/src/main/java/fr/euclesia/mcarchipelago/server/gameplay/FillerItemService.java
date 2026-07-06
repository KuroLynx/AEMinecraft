package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Hands out the Minecraft item stacks granted by <em>item</em> filler items (see
 * {@code minecraft_aem/filler.py} and {@link FillerTrapService}). Every filler item the apworld emits
 * is verified advancement-safe — it appears in no advancement except BACAP's "get/stack each item"
 * challenges and is neither a crafting ingredient nor an access item — so dropping a free stack into
 * the inventory can never complete an advancement or open a location out of logic.
 *
 * <p>The stack is split across the item's max stack size (so a 4-of-boats grant yields four boats,
 * and 64-of-dirt one stack); whatever doesn't fit the inventory is dropped at the player's feet. All
 * work runs on the server thread.
 */
public final class FillerItemService {
    private FillerItemService() {}

    /** Gives {@code count} of the item {@code itemId} ("minecraft:dirt") to {@code player}. */
    public static void give(ServerPlayer player, String itemId, int count) {
        Identifier id = Identifier.parse(itemId);
        Item item = BuiltInRegistries.ITEM.get(id).map(Holder::value).orElse(null);
        if (item == null) {
            AEM.LOGGER.warn("Unknown filler item '{}'", itemId);
            return;
        }
        int max = Math.max(1, new ItemStack(item).getMaxStackSize());
        int remaining = Math.max(0, count);
        while (remaining > 0) {
            int amount = Math.min(remaining, max);
            ItemStack stack = new ItemStack(item, amount);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= amount;
        }
        // Action-bar note so the grant is visible even when the stack merges into an existing one.
        player.sendSystemMessage(
                Component.translatable("filler.aem.item.granted", count, new ItemStack(item).getHoverName()), true);
    }
}
