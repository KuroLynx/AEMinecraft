package fr.euclesia.mcarchipelago.content;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Biome Finder is a vanilla {@link Items#COMPASS} marked with the custom {@link #MARKER}
 * data component, so the vanilla lodestone-compass needle renders it for free (the needle is driven
 * by {@link DataComponents#LODESTONE_TRACKER}, which {@code BiomeFinderService} sets on a pick). The
 * marker is what distinguishes our finder from an ordinary compass for right-click detection and for
 * the keep-on-death (soulbound) handling.
 *
 * <p>Ownership of the finder is derived from the received Archipelago item count, not from the
 * physical stack, so the compass can be regranted freely (on join, respawn, or receipt).
 */
public final class BiomeFinderItem {
    /** Archipelago item whose receipt grants the finder. */
    public static final String AP_ITEM = "Biome Finder";

    /** Marker component identifying a stack as the Biome Finder compass. */
    public static final DataComponentType<Unit> MARKER = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath(AEM.MOD_ID, "biome_finder"),
            DataComponentType.<Unit>builder()
                    .persistent(Unit.CODEC)
                    .networkSynchronized(Unit.STREAM_CODEC)
                    .build());

    private BiomeFinderItem() {}

    /** Loads this class so its static registration runs. Call once during mod init. */
    public static void register() {
        // Touching MARKER above triggers registration on class load; nothing else to do.
    }

    /** A fresh Biome Finder compass: a marked compass with a fixed, non-italic display name. */
    public static ItemStack createStack() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(MARKER, Unit.INSTANCE);
        // ITEM_NAME (not CUSTOM_NAME) gives a default, non-italic name with no anvil/rename semantics.
        stack.set(DataComponents.ITEM_NAME, Component.literal("Biome Finder"));
        return stack;
    }

    /** Whether {@code stack} is a Biome Finder compass. */
    public static boolean isFinder(ItemStack stack) {
        return !stack.isEmpty() && stack.has(MARKER);
    }

    /** Whether {@code player} already carries a Biome Finder. */
    public static boolean has(Player player) {
        return player.getInventory().contains(BiomeFinderItem::isFinder);
    }

    /** The first Biome Finder stack in {@code player}'s inventory, or {@link ItemStack#EMPTY}. */
    public static ItemStack findFirst(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isFinder(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Removes every Biome Finder stack from {@code player}'s inventory (used before death drops). */
    public static void removeAll(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isFinder(inventory.getItem(slot))) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
    }
}
