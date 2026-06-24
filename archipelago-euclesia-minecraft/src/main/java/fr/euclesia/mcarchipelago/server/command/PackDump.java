package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the data-driven content-pack files from RAW datapack resources — the shared core of the
 * {@code /aem dump} command and the title-menu dump UI. It reads exactly what the offline
 * {@code tools/build_*.py} read from the jar (advancements, recipes, loot, trades, tags, worldgen
 * structures, structure NBT) but via a {@link ResourceManager}, so it works with the running game's
 * server data OR a standalone datapack {@link ResourceManager} (no world needed).
 */
public final class PackDump {
    private PackDump() {}

    // serializeNulls so a null field is written as `null` (block_mining "needs", advancement "parent"
    // / "title") instead of being dropped — matching what the offline json.dump tools emit.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    /** Dumpable file ids (UI checkboxes); {@code meta} + {@code acquisition} etc. map to one file each. */
    public static final List<String> FILES = List.of(
            "advancements", "structures", "acquisition", "block_mining", "tags", "meta");

    /** Write the selected files to {@code outDir}; returns a short per-file summary line. */
    public static String run(ResourceManager rm, Path outDir, Set<String> selected) {
        List<String> done = new ArrayList<>();
        try {
            Files.createDirectories(outDir);
            if (selected.contains("advancements")) {
                done.add("advancements " + writeCount(outDir, "manifest.json", advancements(rm)));
            }
            if (selected.contains("structures")) {
                done.add("structures " + write(outDir, "structures.json", structures(rm)).size());
            }
            if (selected.contains("acquisition")) {
                done.add("acquisition " + writeCount(outDir, "acquisition.json", AcquisitionDump.build(rm)));
            }
            if (selected.contains("block_mining")) {
                done.add("block_mining " + writeCount(outDir, "block_mining.json", blockMining(rm)));
            }
            if (selected.contains("tags")) {
                write(outDir, "tags.json", tags(rm));
                done.add("tags");
            }
            if (selected.contains("meta")) {
                write(outDir, "meta.json", meta());
                done.add("meta");
            }
        } catch (Exception exception) {
            return "Dump failed: " + exception;
        }
        return String.join(", ", done);
    }

    // -- advancements (data/<ns>/advancement[s]/*.json) ---------------------

    private static JsonObject advancements(ResourceManager rm) {
        Map<String, JsonObject> records = new TreeMap<>();
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "advancement")) {
            String rel = stripExt(entry.getKey().getPath().substring("advancement/".length()));
            if (rel.startsWith("recipes/")) {
                continue;  // recipe advancements are not checks
            }
            records.put(entry.getKey().getNamespace() + ":" + rel, advancementRecord(entry.getValue(), rel));
        }
        JsonObject out = new JsonObject();
        records.forEach(out::add);
        return out;
    }

    private static JsonObject advancementRecord(JsonObject data, String rel) {
        JsonObject record = new JsonObject();           // keys alphabetical (sort_keys parity)
        JsonObject criteria = new JsonObject();
        for (Map.Entry<String, JsonElement> crit : obj(data.get("criteria")).entrySet()) {
            JsonObject body = obj(crit.getValue());
            JsonObject reshaped = new JsonObject();
            reshaped.add("conditions", body.has("conditions") ? body.get("conditions") : new JsonObject());
            reshaped.add("trigger", body.has("trigger") ? body.get("trigger") : JsonNull.INSTANCE);
            criteria.add(crit.getKey(), reshaped);
        }
        JsonObject display = obj(data.get("display"));
        record.add("criteria", criteria);
        record.addProperty("frame", string(display.get("frame"), "task"));
        record.add("parent", data.has("parent") ? data.get("parent") : JsonNull.INSTANCE);
        record.add("requirements", data.has("requirements") ? data.get("requirements") : new JsonArray());
        record.addProperty("tab", rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel);
        String title = title(display);
        record.add("title", title == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(title));
        return record;
    }

    /** Display title: a literal string, or a {@code translate} key resolved against loaded lang. */
    private static String title(JsonObject display) {
        JsonElement title = display.get("title");
        if (title == null) {
            return null;
        }
        if (title.isJsonPrimitive()) {
            return title.getAsString();
        }
        JsonObject obj = obj(title);
        if (obj.has("translate")) {
            String key = obj.get("translate").getAsString();
            return Language.getInstance().getOrDefault(key);
        }
        return obj.has("text") ? obj.get("text").getAsString() : null;
    }

    // -- block_mining (data/<ns>/tags/block/...) ----------------------------

    private static JsonObject blockMining(ResourceManager rm) {
        TreeSet<String> pickaxe = new TreeSet<>();
        Map<String, String> needs = new HashMap<>();
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "tags/block")) {
            String rel = stripExt(entry.getKey().getPath().substring("tags/block/".length()));
            boolean mineable = rel.equals("mineable/pickaxe");
            String tier = switch (rel) {
                case "needs_stone_tool" -> "stone";
                case "needs_iron_tool" -> "iron";
                case "needs_diamond_tool" -> "diamond";
                default -> null;
            };
            if (!mineable && tier == null) {
                continue;
            }
            for (JsonElement value : array(entry.getValue().get("values"))) {
                String id = tagValueId(value);
                if (id.isEmpty() || id.startsWith("#")) {
                    continue;  // mineable/needs tags list blocks directly
                }
                if (mineable) {
                    pickaxe.add(stripNs(id));
                } else {
                    needs.put(stripNs(id), tier);
                }
            }
        }
        JsonObject out = new JsonObject();
        for (String block : pickaxe) {
            JsonObject record = new JsonObject();
            String tier = needs.get(block);
            record.add("needs", tier == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(tier));
            out.add(block, record);
        }
        return out;
    }

    // -- tags (data/<ns>/tags/{item,block,entity_type}/...) -----------------

    private static JsonObject tags(ResourceManager rm) {
        JsonObject out = new JsonObject();
        out.add("block", tagRegistry(rm, "block"));
        out.add("entity_type", tagRegistry(rm, "entity_type"));
        out.add("item", tagRegistry(rm, "item"));
        return out;
    }

    private static JsonObject tagRegistry(ResourceManager rm, String registry) {
        String prefix = "tags/" + registry + "/";
        Map<String, JsonArray> raw = new HashMap<>();   // "<ns>:<tag>" -> raw values
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "tags/" + registry)) {
            JsonElement values = entry.getValue().get("values");
            if (values != null && values.isJsonArray()) {
                String rel = stripExt(entry.getKey().getPath().substring(prefix.length()));
                raw.put(entry.getKey().getNamespace() + ":" + rel, values.getAsJsonArray());
            }
        }
        Map<String, JsonArray> resolved = new TreeMap<>();
        for (String tag : raw.keySet()) {
            TreeSet<String> members = new TreeSet<>();
            resolveTag(tag, raw, members, new TreeSet<>());
            JsonArray array = new JsonArray();
            members.forEach(array::add);
            resolved.put(tag, array);
        }
        JsonObject out = new JsonObject();
        resolved.forEach(out::add);
        return out;
    }

    private static void resolveTag(String tag, Map<String, JsonArray> raw, Set<String> out, Set<String> seen) {
        if (!seen.add(tag)) {
            return;
        }
        for (JsonElement value : raw.getOrDefault(tag, new JsonArray())) {
            String id = tagValueId(value);
            if (id.isEmpty()) {
                continue;
            }
            if (id.startsWith("#")) {
                resolveTag(namespaced(id.substring(1)), raw, out, seen);
            } else {
                out.add(namespaced(id));
            }
        }
    }

    // -- structures (worldgen/structure/*.json + structure/*.nbt) -----------

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

    private static JsonArray structures(ResourceManager rm) {
        Map<String, JsonObject> structDefs = new HashMap<>();   // game_id -> worldgen json
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "worldgen/structure")) {
            structDefs.put(stripExt(entry.getKey().getPath().substring("worldgen/structure/".length())),
                    entry.getValue());
        }
        Map<String, JsonArray> biomeTags = new HashMap<>();     // bare tag path -> raw values
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "tags/worldgen/biome")) {
            JsonElement values = entry.getValue().get("values");
            if (values != null && values.isJsonArray()) {
                biomeTags.put(stripExt(entry.getKey().getPath().substring("tags/worldgen/biome/".length())),
                        values.getAsJsonArray());
            }
        }
        Map<String, TreeSet<String>> palettes = structurePalettes(rm);

        JsonArray table = new JsonArray();
        for (String[] entry : STRUCTURES) {
            JsonObject record = new JsonObject();
            record.addProperty("name", entry[0]);
            record.addProperty("game_id", entry[1]);
            record.addProperty("region", region(structDefs.get(entry[1]), biomeTags));
            JsonArray blocks = new JsonArray();
            TreeSet<String> palette = palettes.get(entry[0]);
            if (palette != null) {
                palette.forEach(blocks::add);
            }
            record.add("blocks", blocks);
            table.add(record);
        }
        return table;
    }

    private static String region(JsonObject struct, Map<String, JsonArray> biomeTags) {
        if (struct == null) {
            return "Overworld";
        }
        for (JsonElement setup : array(struct.get("setups"))) {
            if (setup.isJsonObject() && "in_nether".equals(string(setup.getAsJsonObject().get("placement"), ""))) {
                return "Nether";
            }
        }
        Set<String> biomes = new TreeSet<>();
        JsonElement b = struct.get("biomes");
        if (b != null && b.isJsonPrimitive()) {
            String value = b.getAsString();
            if (value.startsWith("#")) {
                resolveBiomeTag(stripNs(value.substring(1)), biomeTags, biomes, new TreeSet<>());
            } else {
                biomes.add(stripNs(value));
            }
        } else if (b != null && b.isJsonArray()) {
            for (JsonElement e : b.getAsJsonArray()) {
                biomes.add(stripNs(tagValueId(e)));
            }
        }
        Set<String> nether = new TreeSet<>();
        resolveBiomeTag("is_nether", biomeTags, nether, new TreeSet<>());
        Set<String> end = new TreeSet<>();
        resolveBiomeTag("is_end", biomeTags, end, new TreeSet<>());
        if (!java.util.Collections.disjoint(biomes, nether)) {
            return "Nether";
        }
        if (!java.util.Collections.disjoint(biomes, end)) {
            return "The End";
        }
        return "Overworld";
    }

    private static void resolveBiomeTag(String tag, Map<String, JsonArray> tags, Set<String> out, Set<String> seen) {
        if (!seen.add(tag)) {
            return;
        }
        for (JsonElement value : tags.getOrDefault(tag, new JsonArray())) {
            String id = tagValueId(value);
            if (id.isEmpty()) {
                continue;
            }
            if (id.startsWith("#")) {
                resolveBiomeTag(stripNs(id.substring(1)), tags, out, seen);
            } else {
                out.add(stripNs(id));
            }
        }
    }

    private static Map<String, TreeSet<String>> structurePalettes(ResourceManager rm) {
        Map<String, TreeSet<String>> palettes = new HashMap<>();
        Map<Identifier, Resource> nbts = rm.listResources("structure", id -> id.getPath().endsWith(".nbt"));
        for (Map.Entry<Identifier, Resource> entry : nbts.entrySet()) {
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

    private static String[] nbtStructureNames(String resourcePath) {
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
                default -> VILLAGE_BIOMES.toArray(new String[0]);
            };
        }
        return NBT_STRUCTURE.getOrDefault(parts[0], new String[0]);
    }

    private static TreeSet<String> paletteBlocks(Resource resource) {
        TreeSet<String> blocks = new TreeSet<>();
        try (InputStream stream = resource.open()) {
            CompoundTag root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
            List<ListTag> palettes = new ArrayList<>();
            root.getList("palette").ifPresent(palettes::add);
            root.getList("palettes").ifPresent(list -> {
                for (int i = 0; i < list.size(); i++) {
                    list.getList(i).ifPresent(palettes::add);
                }
            });
            for (ListTag palette : palettes) {
                for (int i = 0; i < palette.size(); i++) {
                    palette.getCompound(i).ifPresent(state -> {
                        String name = state.getStringOr("Name", "");
                        if (!name.isEmpty()) {
                            blocks.add(stripNs(name));
                        }
                    });
                }
            }
        } catch (Exception ignored) {
            // a template that won't parse contributes no palette
        }
        return blocks;
    }

    // -- meta ---------------------------------------------------------------

    private static JsonObject meta() {
        String version = SharedConstants.getCurrentVersion().name();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", "dump");
        meta.addProperty("source", "dump");
        meta.addProperty("namespace", "minecraft");
        meta.addProperty("mc_version", version);
        meta.addProperty("description", "Dumped from the running game (MC " + version + ").");
        return meta;
    }

    // -- resource / json helpers --------------------------------------------

    /** Every {@code <prefix>/**.json} resource as (Identifier, parsed json), id-sorted. Skips bad json.
     *  The Identifier keeps the namespace, so mod/datapack tag + advancement ids stay correct. */
    private static List<Map.Entry<Identifier, JsonObject>> jsonResources(ResourceManager rm, String prefix) {
        Map<Identifier, Resource> found = rm.listResources(prefix, id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(found.entrySet());
        sorted.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
        List<Map.Entry<Identifier, JsonObject>> out = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : sorted) {
            try (Reader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) {
                    out.add(Map.entry(entry.getKey(), parsed.getAsJsonObject()));
                }
            } catch (Exception ignored) {
                // skip
            }
        }
        return out;
    }

    private static String namespaced(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String tagValueId(JsonElement value) {
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonObject()) {
            return string(value.getAsJsonObject().get("id"), "");
        }
        return "";
    }

    private static int writeCount(Path dir, String file, JsonObject json) throws Exception {
        write(dir, file, json);
        return json.size();
    }

    private static <T extends JsonElement> T write(Path dir, String file, T json) throws Exception {
        Files.writeString(dir.resolve(file), GSON.toJson(json));
        return json;
    }

    private static Iterable<JsonElement> array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject obj(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonElement element, String fallback) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static String stripNs(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String stripExt(String path) {
        return path.endsWith(".json") ? path.substring(0, path.length() - ".json".length()) : path;
    }
}
