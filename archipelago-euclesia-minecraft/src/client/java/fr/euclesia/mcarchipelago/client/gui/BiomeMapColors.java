package fr.euclesia.mcarchipelago.client.gui;

import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.Map;

/**
 * Map colours for the Biome Finder screen, one per vanilla biome, in the palette biome-map tools have
 * made familiar (blue oceans, sandy deserts, magenta mushroom fields). A biome not listed here — a new
 * vanilla one or a datapack's — gets a stable colour hashed from its id, so it still reads as its own
 * patch.
 *
 * <p>ponytail: hand-picked table; derive from the biome's own grass/water colours if datapack biomes
 * become common enough that hashed colours confuse people.
 */
final class BiomeMapColors {
    private static final Map<String, Integer> COLORS = Map.ofEntries(
            // Water
            Map.entry("ocean", 0x000070), Map.entry("deep_ocean", 0x000030),
            Map.entry("warm_ocean", 0x0000AC), Map.entry("lukewarm_ocean", 0x000090),
            Map.entry("deep_lukewarm_ocean", 0x000040), Map.entry("cold_ocean", 0x202070),
            Map.entry("deep_cold_ocean", 0x202038), Map.entry("frozen_ocean", 0x7070D6),
            Map.entry("deep_frozen_ocean", 0x404090), Map.entry("river", 0x0000FF),
            Map.entry("frozen_river", 0xA0A0FF),
            // Shores
            Map.entry("beach", 0xFADE55), Map.entry("snowy_beach", 0xFAF0C0), Map.entry("stony_shore", 0xA2A284),
            // Flat and temperate
            Map.entry("plains", 0x8DB360), Map.entry("sunflower_plains", 0xB5DB88), Map.entry("meadow", 0x9CCB6C),
            Map.entry("forest", 0x056621), Map.entry("flower_forest", 0x2D8E49), Map.entry("birch_forest", 0x307444),
            Map.entry("old_growth_birch_forest", 0x589C6C), Map.entry("dark_forest", 0x40511A),
            Map.entry("pale_garden", 0xB9BBB6), Map.entry("cherry_grove", 0xFFB7D5),
            Map.entry("swamp", 0x07F9B2), Map.entry("mangrove_swamp", 0x2CCC8E),
            Map.entry("mushroom_fields", 0xFF00FF),
            // Cold
            Map.entry("taiga", 0x0B6659), Map.entry("snowy_taiga", 0x31554A),
            Map.entry("old_growth_pine_taiga", 0x596651), Map.entry("old_growth_spruce_taiga", 0x818E79),
            Map.entry("snowy_plains", 0xFFFFFF), Map.entry("ice_spikes", 0xB4DCDC), Map.entry("grove", 0x47726C),
            Map.entry("snowy_slopes", 0xC4C4C4), Map.entry("frozen_peaks", 0xA0A0E0),
            Map.entry("jagged_peaks", 0xDCDCC8), Map.entry("stony_peaks", 0x7B8F74),
            // Hills
            Map.entry("windswept_hills", 0x606060), Map.entry("windswept_gravelly_hills", 0x888888),
            Map.entry("windswept_forest", 0x507050),
            // Hot
            Map.entry("desert", 0xFA9418), Map.entry("savanna", 0xBDB25F), Map.entry("savanna_plateau", 0xA79D64),
            Map.entry("windswept_savanna", 0xE5DA87), Map.entry("jungle", 0x537B09),
            Map.entry("sparse_jungle", 0x628B17), Map.entry("bamboo_jungle", 0x768E14),
            Map.entry("badlands", 0xD94515), Map.entry("eroded_badlands", 0xFF6D3D),
            Map.entry("wooded_badlands", 0xB09765),
            // Underground
            Map.entry("dripstone_caves", 0x866043), Map.entry("lush_caves", 0x7BA331), Map.entry("deep_dark", 0x0F252F),
            // Nether
            Map.entry("nether_wastes", 0xBF3B3B), Map.entry("soul_sand_valley", 0x5E3830),
            Map.entry("crimson_forest", 0xDD0808), Map.entry("warped_forest", 0x49907B),
            Map.entry("basalt_deltas", 0x403636),
            // End
            Map.entry("the_end", 0x8080FF), Map.entry("small_end_islands", 0x4B4BAB),
            Map.entry("end_midlands", 0xC9C959), Map.entry("end_highlands", 0xB5B536),
            Map.entry("end_barrens", 0x7070CC), Map.entry("the_void", 0x101010));

    private BiomeMapColors() {}

    /** The opaque ARGB colour for a biome. */
    static int argb(Identifier biome) {
        Integer rgb = "minecraft".equals(biome.getNamespace()) ? COLORS.get(biome.getPath()) : null;
        if (rgb == null) {
            // Any stable, reasonably saturated colour: the hash picks the hue.
            float hue = (biome.toString().hashCode() & 0xFFFF) / 65536.0F;
            rgb = Mth.hsvToRgb(hue, 0.55F, 0.8F) & 0xFFFFFF;
        }
        return 0xFF000000 | rgb;
    }
}
