package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code /aem dump <what>} — exports the data-driven content-pack files from the RUNNING game
 * (vanilla + mods + datapacks), the in-game counterpart of the offline {@code tools/build_*.py}.
 * Each subcommand targets one pack file and writes the SAME schema its Python tool does, so the
 * apworld ingests either source identically. With no argument it dumps the whole pack.
 *
 * <ul>
 *   <li>{@code pack}         — every file below (the default).</li>
 *   <li>{@code advancements} — {@code manifest.json} (was {@code dump-advancements}).</li>
 *   <li>{@code structures}   — {@code structures.json} (region from biome tags, palette from templates).</li>
 *   <li>{@code acquisition}  — {@code acquisition.json} (recipes / loot / trades / food; {@link AcquisitionDump}).</li>
 *   <li>{@code block_mining} — {@code block_mining.json} (pickaxe-mineable blocks + tool tier).</li>
 *   <li>{@code tags}         — {@code tags.json} (item / block / entity_type tags, flattened).</li>
 *   <li>{@code meta}         — {@code meta.json} (pack name / namespace / mc_version).</li>
 * </ul>
 *
 * Files land in {@code <gameDir>/aem/}. {@code brewing.json} stays curated (MC brewing is hard-coded
 * with no recipe data), like {@code items.csv} / {@code mobs.csv}.
 */
public final class DumpCommandModule implements AEMCommandModule {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("dump")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> dumpPack(context.getSource()))            // /aem dump -> whole pack
                .then(Commands.literal("pack").executes(c -> dumpPack(c.getSource())))
                .then(Commands.literal("advancements").executes(c -> dumpAdvancements(c.getSource())))
                .then(Commands.literal("structures").executes(c -> dumpStructures(c.getSource())))
                .then(Commands.literal("acquisition").executes(c -> dumpAcquisition(c.getSource())))
                .then(Commands.literal("block_mining").executes(c -> dumpBlockMining(c.getSource())))
                .then(Commands.literal("tags").executes(c -> dumpTags(c.getSource())))
                .then(Commands.literal("meta").executes(c -> dumpMeta(c.getSource()))));
    }

    // -- pack ---------------------------------------------------------------

    private static int dumpPack(CommandSourceStack source) {
        int ok = 0;
        ok += dumpAdvancements(source);
        ok += dumpStructures(source);
        ok += dumpAcquisition(source);
        ok += dumpBlockMining(source);
        ok += dumpTags(source);
        ok += dumpMeta(source);
        source.sendSuccess(() -> Component.literal("Pack dump complete (" + outDir() + ")."), true);
        return ok;
    }

    // -- advancements -> manifest.json --------------------------------------

    private static int dumpAdvancements(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());

        Map<String, JsonObject> records = new TreeMap<>();  // id-sorted, matches extract_manifest.py
        int skipped = 0;
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            Identifier id = holder.id();
            String path = id.getPath();
            if (path.startsWith("recipes/")) {
                skipped++;
                continue;
            }
            JsonObject full = Advancement.CODEC.encodeStart(ops, holder.value())
                    .result()
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .orElse(null);
            if (full == null) {
                skipped++;
                continue;
            }
            records.put(id.toString(), reshapeAdvancement(full, path));
        }

        JsonObject manifest = new JsonObject();
        records.forEach(manifest::add);
        if (!write(source, "manifest.json", manifest)) {
            return 0;
        }
        int written = records.size();
        int skippedRecipes = skipped;
        source.sendSuccess(() -> Component.literal(
                "  advancements: " + written + " (" + skippedRecipes + " recipes skipped)"), false);
        return 1;
    }

    /** Pull the manifest fields out of a codec-encoded advancement JSON. */
    private static JsonObject reshapeAdvancement(JsonObject full, String path) {
        JsonObject record = new JsonObject();
        record.add("parent", full.has("parent") ? full.get("parent") : JsonNull.INSTANCE);
        record.addProperty("tab", path.contains("/") ? path.substring(0, path.indexOf('/')) : path);
        String frame = "task";
        if (full.has("display")) {
            JsonObject display = full.getAsJsonObject("display");
            if (display.has("frame")) {
                frame = display.get("frame").getAsString();
            }
        }
        record.addProperty("frame", frame);
        record.add("requirements", full.has("requirements") ? full.get("requirements") : new JsonArray());
        record.add("criteria", full.has("criteria") ? full.get("criteria") : new JsonObject());
        return record;
    }

    // -- tags -> tags.json --------------------------------------------------

    private static int dumpTags(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        JsonObject table = new JsonObject();
        table.add("item", tagsFor(server, Registries.ITEM));
        table.add("entity_type", tagsFor(server, Registries.ENTITY_TYPE));
        table.add("block", tagsFor(server, Registries.BLOCK));
        if (!write(source, "tags.json", table)) {
            return 0;
        }
        int item = table.getAsJsonObject("item").size();
        int entity = table.getAsJsonObject("entity_type").size();
        int block = table.getAsJsonObject("block").size();
        source.sendSuccess(() -> Component.literal(
                "  tags: item " + item + " | entity_type " + entity + " | block " + block), false);
        return 1;
    }

    /** Each tag in a registry flattened to its concrete member ids (the registry resolves nesting). */
    private static <T> JsonObject tagsFor(MinecraftServer server, ResourceKey<Registry<T>> registryKey) {
        Registry<T> registry = server.registryAccess().lookupOrThrow(registryKey);
        Map<String, JsonArray> sorted = new TreeMap<>();
        for (HolderSet.Named<T> named : (Iterable<HolderSet.Named<T>>) registry.getTags()::iterator) {
            TreeSet<String> members = new TreeSet<>();
            for (Holder<T> holder : named) {
                Identifier id = registry.getKey(holder.value());
                if (id != null) {
                    members.add(id.toString());
                }
            }
            JsonArray array = new JsonArray();
            members.forEach(array::add);
            sorted.put(named.key().location().toString(), array);
        }
        JsonObject out = new JsonObject();
        sorted.forEach(out::add);
        return out;
    }

    // -- acquisition -> acquisition.json ------------------------------------

    private static int dumpAcquisition(CommandSourceStack source) {
        JsonObject table = AcquisitionDump.build(source.getServer());
        if (!write(source, "acquisition.json", table)) {
            return 0;
        }
        int count = table.size();
        source.sendSuccess(() -> Component.literal("  acquisition: " + count + " items"), false);
        return 1;
    }

    // -- structures -> structures.json --------------------------------------

    // Curated identity + stable id order (mirrors tools/build_structures.py); region/blocks derived.
    private static final String[][] STRUCTURES = {
            {"Ancient City", "ancient_city"}, {"Bastion Remnant", "bastion_remnant"},
            {"Buried Treasure", "buried_treasure"}, {"Desert Pyramid", "desert_pyramid"},
            {"End City", "end_city"}, {"Nether Fortress", "fortress"}, {"Igloo", "igloo"},
            {"Jungle Pyramid", "jungle_pyramid"}, {"Mansion", "mansion"}, {"Mineshaft", "mineshaft"},
            {"Mineshaft (Mesa)", "mineshaft_mesa"}, {"Ocean Monument", "monument"},
            {"Nether Fossil", "nether_fossil"}, {"Ocean Ruin (Cold)", "ocean_ruin_cold"},
            {"Ocean Ruin (Warm)", "ocean_ruin_warm"}, {"Pillager Outpost", "pillager_outpost"},
            {"Ruined Portal", "ruined_portal"}, {"Ruined Portal (Desert)", "ruined_portal_desert"},
            {"Ruined Portal (Jungle)", "ruined_portal_jungle"},
            {"Ruined Portal (Mountain)", "ruined_portal_mountain"},
            {"Ruined Portal (Nether)", "ruined_portal_nether"},
            {"Ruined Portal (Ocean)", "ruined_portal_ocean"},
            {"Ruined Portal (Swamp)", "ruined_portal_swamp"}, {"Shipwreck", "shipwreck"},
            {"Shipwreck (Beached)", "shipwreck_beached"}, {"Stronghold", "stronghold"},
            {"Swamp Hut", "swamp_hut"}, {"Trail Ruins", "trail_ruins"},
            {"Trial Chambers", "trial_chambers"}, {"Village (Desert)", "village_desert"},
            {"Village (Plains)", "village_plains"}, {"Village (Savanna)", "village_savanna"},
            {"Village (Snowy)", "village_snowy"}, {"Village (Taiga)", "village_taiga"},
            {"Dungeon", "monster_room"}, {"Desert Well", "desert_well"},
    };

    // Structure-template top folder (data/<ns>/structure/<top>/...) -> canonical structure name(s).
    private static final Map<String, String[]> NBT_STRUCTURE = Map.ofEntries(
            Map.entry("ancient_city", new String[]{"Ancient City"}),
            Map.entry("bastion", new String[]{"Bastion Remnant"}),
            Map.entry("end_city", new String[]{"End City"}),
            Map.entry("igloo", new String[]{"Igloo"}),
            Map.entry("nether_fossils", new String[]{"Nether Fossil"}),
            Map.entry("pillager_outpost", new String[]{"Pillager Outpost"}),
            Map.entry("ruined_portal", new String[]{"Ruined Portal"}),
            Map.entry("shipwreck", new String[]{"Shipwreck", "Shipwreck (Beached)"}),
            Map.entry("trail_ruins", new String[]{"Trail Ruins"}),
            Map.entry("trial_chambers", new String[]{"Trial Chambers"}),
            Map.entry("underwater_ruin", new String[]{"Ocean Ruin (Cold)", "Ocean Ruin (Warm)"}),
            Map.entry("woodland_mansion", new String[]{"Mansion"}));
    private static final List<String> VILLAGE_BIOMES = List.of(
            "Village (Desert)", "Village (Plains)", "Village (Savanna)",
            "Village (Snowy)", "Village (Taiga)");

    private static int dumpStructures(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Map<String, TreeSet<String>> palettes = structurePalettes(server);
        Registry<Structure> registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);

        JsonArray table = new JsonArray();
        int withPalette = 0;
        for (String[] entry : STRUCTURES) {
            String name = entry[0];
            String gameId = entry[1];
            JsonObject record = new JsonObject();
            record.addProperty("name", name);
            record.addProperty("game_id", gameId);
            record.addProperty("region", structureRegion(registry, gameId));
            JsonArray blocks = new JsonArray();
            TreeSet<String> palette = palettes.get(name);
            if (palette != null) {
                palette.forEach(blocks::add);
                withPalette++;
            }
            record.add("blocks", blocks);
            table.add(record);
        }
        if (!write(source, "structures.json", table)) {
            return 0;
        }
        int total = STRUCTURES.length;
        int paletted = withPalette;
        source.sendSuccess(() -> Component.literal(
                "  structures: " + total + " (" + paletted + " with palette)"), false);
        return 1;
    }

    /** Dimension a structure generates in, from its biome set (Nether/End biome tags). */
    private static String structureRegion(Registry<Structure> registry, String gameId) {
        Structure structure = registry.getValue(Identifier.withDefaultNamespace(gameId));
        if (structure == null) {
            return "Overworld";  // a feature with no worldgen structure (dungeon, desert well)
        }
        HolderSet<net.minecraft.world.level.biome.Biome> biomes = structure.biomes();
        for (Holder<net.minecraft.world.level.biome.Biome> biome : biomes) {
            if (biome.is(net.minecraft.tags.BiomeTags.IS_NETHER)) {
                return "Nether";
            }
            if (biome.is(net.minecraft.tags.BiomeTags.IS_END)) {
                return "The End";
            }
        }
        return "Overworld";
    }

    /** Canonical structure name -> the block ids of every NBT template it is built from. */
    private static Map<String, TreeSet<String>> structurePalettes(MinecraftServer server) {
        Map<String, TreeSet<String>> palettes = new java.util.HashMap<>();
        Map<Identifier, net.minecraft.server.packs.resources.Resource> resources =
                server.getResourceManager().listResources(
                        "structure", id -> id.getPath().endsWith(".nbt"));
        for (Map.Entry<Identifier, net.minecraft.server.packs.resources.Resource> entry : resources.entrySet()) {
            String[] names = nbtStructureNames(entry.getKey().getPath());
            if (names.length == 0) {
                continue;
            }
            TreeSet<String> blocks = paletteBlocks(entry.getValue());
            for (String name : names) {
                palettes.computeIfAbsent(name, k -> new TreeSet<>()).addAll(blocks);
            }
        }
        return palettes;
    }

    /** Canonical structure name(s) a {@code structure/...nbt} resource path belongs to. */
    private static String[] nbtStructureNames(String resourcePath) {
        // resourcePath is e.g. "structure/ancient_city/city/entrance/...nbt".
        String rel = resourcePath.substring("structure/".length(), resourcePath.length() - ".nbt".length());
        String[] parts = rel.split("/");
        if (parts[0].equals("village")) {
            String biome = parts.length > 1 ? parts[1] : "";
            return switch (biome) {
                case "desert" -> new String[]{"Village (Desert)"};
                case "plains" -> new String[]{"Village (Plains)"};
                case "savanna" -> new String[]{"Village (Savanna)"};
                case "snowy" -> new String[]{"Village (Snowy)"};
                case "taiga" -> new String[]{"Village (Taiga)"};
                default -> VILLAGE_BIOMES.toArray(new String[0]);  // common / decays — every village
            };
        }
        return NBT_STRUCTURE.getOrDefault(parts[0], new String[0]);
    }

    /** Every block id named in a structure template's palette(s). */
    private static TreeSet<String> paletteBlocks(net.minecraft.server.packs.resources.Resource resource) {
        TreeSet<String> blocks = new TreeSet<>();
        try (java.io.InputStream stream = resource.open()) {
            net.minecraft.nbt.CompoundTag root =
                    net.minecraft.nbt.NbtIo.readCompressed(stream, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            java.util.List<net.minecraft.nbt.ListTag> palettes = new java.util.ArrayList<>();
            root.getList("palette").ifPresent(palettes::add);
            root.getList("palettes").ifPresent(list -> {
                for (int i = 0; i < list.size(); i++) {
                    list.getList(i).ifPresent(palettes::add);
                }
            });
            for (net.minecraft.nbt.ListTag palette : palettes) {
                for (int i = 0; i < palette.size(); i++) {
                    palette.getCompound(i).ifPresent(state -> {
                        String name = state.getStringOr("Name", "");
                        if (!name.isEmpty()) {
                            blocks.add(stripNamespace(name));
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // a template that won't parse contributes no palette
        }
        return blocks;
    }

    private static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // -- block_mining -> block_mining.json ----------------------------------

    private static int dumpBlockMining(CommandSourceStack source) {
        Registry<Block> registry = source.getServer().registryAccess().lookupOrThrow(Registries.BLOCK);
        TreeSet<String> pickaxe = new TreeSet<>();          // pickaxe-mineable block paths (sorted)
        Map<String, String> needs = new java.util.HashMap<>();  // block path -> tool tier
        for (HolderSet.Named<Block> named : (Iterable<HolderSet.Named<Block>>) registry.getTags()::iterator) {
            String tagPath = named.key().location().getPath();  // namespace-agnostic, like the offline tool
            boolean mineable = tagPath.equals("mineable/pickaxe");
            String tier = switch (tagPath) {
                case "needs_stone_tool" -> "stone";
                case "needs_iron_tool" -> "iron";
                case "needs_diamond_tool" -> "diamond";
                default -> null;
            };
            if (!mineable && tier == null) {
                continue;
            }
            for (Holder<Block> holder : named) {
                Identifier id = registry.getKey(holder.value());
                if (id == null) {
                    continue;
                }
                if (mineable) {
                    pickaxe.add(id.getPath());
                } else {
                    needs.put(id.getPath(), tier);
                }
            }
        }
        JsonObject table = new JsonObject();  // only pickaxe-mineable blocks; tier from needs_*_tool
        for (String block : pickaxe) {
            JsonObject record = new JsonObject();
            String tier = needs.get(block);
            record.add("needs", tier == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(tier));
            table.add(block, record);
        }
        if (!write(source, "block_mining.json", table)) {
            return 0;
        }
        int count = pickaxe.size();
        source.sendSuccess(() -> Component.literal("  block_mining: " + count + " pickaxe-mineable blocks"), false);
        return 1;
    }

    // -- meta -> meta.json --------------------------------------------------

    private static int dumpMeta(CommandSourceStack source) {
        String version = SharedConstants.getCurrentVersion().name();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", "vanilla_dump");
        meta.addProperty("source", "dump");
        meta.addProperty("namespace", "minecraft");
        meta.addProperty("mc_version", version);
        meta.addProperty("description", "Dumped from the running game (MC " + version + ").");
        if (!write(source, "meta.json", meta)) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("  meta: MC " + version), false);
        return 1;
    }

    // -- io -----------------------------------------------------------------

    private static Path outDir() {
        return FabricLoader.getInstance().getGameDir().resolve("aem");
    }

    private static boolean write(CommandSourceStack source, String fileName, JsonElement json) {
        Path out = outDir().resolve(fileName);
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON.toJson(json));
            return true;
        } catch (IOException exception) {
            source.sendFailure(Component.literal("Failed to write " + fileName + ": " + exception.getMessage()));
            return false;
        }
    }
}
