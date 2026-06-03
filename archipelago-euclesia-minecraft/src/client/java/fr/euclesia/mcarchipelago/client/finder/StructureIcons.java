package fr.euclesia.mcarchipelago.client.finder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Icons for the Structure-Finder bar. The main icon is the structure's representative item, kept in
 * sync with {@code tools/gen_tracker_advancements.py} {@code STRUCTURE_ICONS} so the bar and the
 * tracker tiles agree. Because biome variants share a main icon (every village is an emerald, every
 * ruined portal is obsidian, …), a small <em>badge</em> item is overlaid to show the variant's biome
 * (desert → sand, snowy → snow, savanna → acacia sapling, …). Keyed by structure path (after
 * {@code namespace:}); resolved stacks are cached and reused for rendering.
 */
public final class StructureIcons {
    private static final String FALLBACK = "minecraft:chest";

    private static final Map<String, String> ITEM_BY_SLUG = Map.ofEntries(
            Map.entry("ancient_city", "minecraft:sculk_catalyst"),
            Map.entry("bastion_remnant", "minecraft:polished_blackstone_bricks"),
            Map.entry("buried_treasure", "minecraft:heart_of_the_sea"),
            Map.entry("desert_pyramid", "minecraft:sandstone"),
            Map.entry("end_city", "minecraft:purpur_block"),
            Map.entry("fortress", "minecraft:nether_bricks"),
            Map.entry("igloo", "minecraft:snow_block"),
            Map.entry("jungle_pyramid", "minecraft:mossy_cobblestone"),
            Map.entry("mansion", "minecraft:dark_oak_planks"),
            Map.entry("mineshaft", "minecraft:rail"),
            Map.entry("mineshaft_mesa", "minecraft:powered_rail"),
            Map.entry("monument", "minecraft:prismarine"),
            Map.entry("nether_fossil", "minecraft:bone_block"),
            Map.entry("ocean_ruin_cold", "minecraft:trident"),
            Map.entry("ocean_ruin_warm", "minecraft:trident"),
            Map.entry("pillager_outpost", "minecraft:crossbow"),
            Map.entry("ruined_portal", "minecraft:obsidian"),
            Map.entry("ruined_portal_desert", "minecraft:obsidian"),
            Map.entry("ruined_portal_jungle", "minecraft:obsidian"),
            Map.entry("ruined_portal_mountain", "minecraft:obsidian"),
            Map.entry("ruined_portal_nether", "minecraft:obsidian"),
            Map.entry("ruined_portal_ocean", "minecraft:obsidian"),
            Map.entry("ruined_portal_swamp", "minecraft:obsidian"),
            Map.entry("shipwreck", "minecraft:oak_boat"),
            Map.entry("shipwreck_beached", "minecraft:oak_boat"),
            Map.entry("stronghold", "minecraft:end_portal_frame"),
            Map.entry("swamp_hut", "minecraft:cauldron"),
            Map.entry("trail_ruins", "minecraft:brush"),
            Map.entry("trial_chambers", "minecraft:trial_key"),
            Map.entry("village_desert", "minecraft:emerald"),
            Map.entry("village_plains", "minecraft:emerald"),
            Map.entry("village_savanna", "minecraft:emerald"),
            Map.entry("village_snowy", "minecraft:emerald"),
            Map.entry("village_taiga", "minecraft:emerald"),
            Map.entry("monster_room", "minecraft:spawner"),
            Map.entry("desert_well", "minecraft:sandstone_slab")
    );

    /** Small biome badge per variant, overlaid on the main icon to disambiguate shared icons. */
    private static final Map<String, String> BADGE_BY_SLUG = Map.ofEntries(
            Map.entry("village_plains", "minecraft:grass_block"),
            Map.entry("village_desert", "minecraft:sand"),
            Map.entry("village_savanna", "minecraft:acacia_sapling"),
            Map.entry("village_snowy", "minecraft:snow_block"),
            Map.entry("village_taiga", "minecraft:spruce_sapling"),
            Map.entry("ruined_portal_desert", "minecraft:sand"),
            Map.entry("ruined_portal_jungle", "minecraft:jungle_sapling"),
            Map.entry("ruined_portal_mountain", "minecraft:stone"),
            Map.entry("ruined_portal_nether", "minecraft:netherrack"),
            Map.entry("ruined_portal_ocean", "minecraft:prismarine"),
            Map.entry("ruined_portal_swamp", "minecraft:lily_pad"),
            Map.entry("shipwreck_beached", "minecraft:sand"),
            Map.entry("ocean_ruin_cold", "minecraft:packed_ice"),
            Map.entry("ocean_ruin_warm", "minecraft:sand")
    );

    private static final Map<String, ItemStack> ICON_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ItemStack> BADGE_CACHE = new ConcurrentHashMap<>();

    private StructureIcons() {}

    /** A (cached, render-only) icon stack for the structure game id, e.g. {@code minecraft:stronghold}. */
    public static ItemStack iconFor(String structureGameId) {
        return ICON_CACHE.computeIfAbsent(structureGameId,
                id -> stack(ITEM_BY_SLUG.getOrDefault(slug(id), FALLBACK)));
    }

    /** The biome-badge stack for a structure, or {@link ItemStack#EMPTY} when the variant needs none. */
    public static ItemStack badgeFor(String structureGameId) {
        return BADGE_CACHE.computeIfAbsent(structureGameId, id -> {
            String itemId = BADGE_BY_SLUG.get(slug(id));
            return itemId == null ? ItemStack.EMPTY : stack(itemId);
        });
    }

    private static ItemStack stack(String itemId) {
        int colon = itemId.indexOf(':');
        Identifier id = colon < 0
                ? Identifier.withDefaultNamespace(itemId)
                : Identifier.fromNamespaceAndPath(itemId.substring(0, colon), itemId.substring(colon + 1));
        return BuiltInRegistries.ITEM.get(id)
                .map(Holder::value)
                .map(ItemStack::new)
                .orElseGet(() -> new ItemStack(Items.CHEST));
    }

    private static String slug(String gameId) {
        int colon = gameId.indexOf(':');
        return colon < 0 ? gameId : gameId.substring(colon + 1);
    }
}
