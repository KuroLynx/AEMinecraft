package fr.euclesia.mcarchipelago.client.finder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Representative item icon per structure type, drawn on the Structure-Finder locator bar. Kept in
 * sync with {@code tools/gen_tracker_advancements.py} {@code STRUCTURE_ICONS} so the bar and the
 * tracker-tab tiles use the same item for a given structure. Keyed by the structure path (the part
 * after {@code namespace:}). Resolved {@link ItemStack}s are cached and reused for rendering.
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
            Map.entry("ocean_ruin_cold", "minecraft:prismarine_bricks"),
            Map.entry("ocean_ruin_warm", "minecraft:cut_sandstone"),
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

    private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();

    private StructureIcons() {}

    /** A (cached, render-only) icon stack for the structure game id, e.g. {@code minecraft:stronghold}. */
    public static ItemStack iconFor(String structureGameId) {
        return CACHE.computeIfAbsent(structureGameId, StructureIcons::resolve);
    }

    private static ItemStack resolve(String structureGameId) {
        String slug = slug(structureGameId);
        String itemId = ITEM_BY_SLUG.getOrDefault(slug, FALLBACK);
        return stack(itemId);
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
