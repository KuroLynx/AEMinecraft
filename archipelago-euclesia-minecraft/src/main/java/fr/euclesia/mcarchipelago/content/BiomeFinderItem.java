package fr.euclesia.mcarchipelago.content;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The Biome Finder is a dedicated custom item ({@code aem:biome_finder}), not a repurposed vanilla
 * compass. Its client model ({@code assets/aem/items/biome_finder.json}) mirrors the vanilla compass
 * range-dispatch on the {@code minecraft:compass} model property, so the lodestone needle still points
 * at the tracked biome — the needle is driven by the {@code LODESTONE_TRACKER} component that
 * {@code BiomeFinderService} sets on a pick, independent of the item class.
 *
 * <p>Ownership of the finder is derived from the received Archipelago item count, not from the
 * physical stack, so it can be regranted freely (on join, respawn, or receipt).
 */
public final class BiomeFinderItem {
    /** Archipelago item whose receipt grants the finder. */
    public static final String AP_ITEM = "Biome Finder";

    /** Registry id / item key for the custom finder item. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "biome_finder");
    private static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, ID);

    /** The custom finder item, registered on class load (see {@link #register()}). */
    public static final Item BIOME_FINDER = Registry.register(
            BuiltInRegistries.ITEM, KEY,
            new Item(new Item.Properties().setId(KEY).stacksTo(1)));

    private BiomeFinderItem() {}

    /** Loads this class so its static registration runs. Call once during mod init (before freeze). */
    public static void register() {
        // Touching BIOME_FINDER above triggers registration on class load; nothing else to do.
    }

    /** A fresh Biome Finder. Its name comes from the {@code item.aem.biome_finder} lang key. */
    public static ItemStack createStack() {
        return new ItemStack(BIOME_FINDER);
    }

    /** Whether {@code stack} is a Biome Finder. */
    public static boolean isFinder(ItemStack stack) {
        return !stack.isEmpty() && stack.is(BIOME_FINDER);
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
