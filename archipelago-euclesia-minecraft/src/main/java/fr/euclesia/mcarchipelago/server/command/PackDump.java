package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.euclesia.mcarchipelago.AEM;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            "advancements", "structures", "acquisition", "block_mining", "block_biomes", "brewing", "tags", "meta");

    /** Opt-in heavy target: copy the loaded datapacks VERBATIM into {@code <outDir>/datapack/} as a
     *  real, loadable datapack tree (not the derived content-pack JSON). Not part of the default
     *  "pack" set ({@link #FILES}) because it duplicates the entire vanilla data tree. */
    public static final String RAW_DATAPACK = "datapack";

    /**
     * Dump the selected files for EACH loaded source pack into its own
     * {@code outDir/<namespace>_<version>/} content-pack folder, so vanilla, mods (Twilight Forest,
     * AoA, …) and folder datapacks (BACAP) each come out as a distinct pack instead of one merged
     * set. {@code <namespace>} is the pack's primary data namespace ({@code minecraft} for vanilla);
     * {@code <version>} is the matching version (mc version for vanilla, the mod version for a mod,
     * or a version parsed from a folder datapack's id). Returns a per-pack summary line.
     */
    public static String run(ResourceManager rm, Path outDir, Set<String> selected) {
        List<String> done = new ArrayList<>();
        try {
            Files.createDirectories(outDir);
            TreeSet<String> used = new TreeSet<>();
            for (PackResources pack : rm.listPacks().toList()) {
                Set<String> namespaces = pack.getNamespaces(PackType.SERVER_DATA);
                if (namespaces.isEmpty()) {
                    continue;
                }
                String ns = primaryNamespace(pack);
                String source = sourceLabel(pack, ns);
                String folder = uniqueName(ns + "_" + versionForPack(pack, ns), used);
                done.add(dumpPack(pack, namespaces, outDir.resolve(folder), folder, ns, source, selected));
            }
        } catch (Exception exception) {
            return "Dump failed: " + exception;
        }
        return String.join(" | ", done);
    }

    /**
     * Dump exactly the SELECTED files for one source pack into its own content-pack folder, reading
     * only that pack's content. What to dump is the user's choice (the checkboxes): a pure datapack
     * like BACAP would be dumped with only manifest/tags/meta ticked, while a datapack that DOES add
     * structures gets those ticked too. Files a pack legitimately doesn't provide are filled in at
     * load time from the base {@code minecraft_<ver>} pack via this pack's {@code meta.mc_version}.
     */
    private static String dumpPack(PackResources pack, Set<String> namespaces, Path packDir,
                                   String folder, String ns, String source, Set<String> selected) throws Exception {
        Files.createDirectories(packDir);
        // a resource manager scoped to just this pack, so each builder sees only its own content
        ResourceManager rm = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack));
        List<String> done = new ArrayList<>();
        if (selected.contains("advancements")) {
            done.add("advancements " + writeCount(packDir, "manifest.json", advancements(rm)));
        }
        if (selected.contains("structures")) {
            done.add("structures " + write(packDir, "structures.json", structures(rm)).size());
        }
        if (selected.contains("acquisition")) {
            done.add("acquisition " + writeCount(packDir, "acquisition.json", AcquisitionDump.build(rm, ns)));
        }
        if (selected.contains("block_mining")) {
            done.add("block_mining " + writeCount(packDir, "block_mining.json", blockMining(rm)));
        }
        if (selected.contains("block_biomes")) {
            done.add("block_biomes " + writeCount(packDir, "block_biomes.json", blockBiomes(rm)));
        }
        if (selected.contains("brewing")) {
            JsonObject brewing = brewing(rm);
            if (brewing != null) {   // no brewing recipes (before 26.3): the curated table stands
                done.add("brewing " + writeCount(packDir, "brewing.json", brewing));
            }
        }
        if (selected.contains("tags")) {
            write(packDir, "tags.json", tags(rm));
            done.add("tags");
        }
        if (selected.contains("meta")) {
            write(packDir, "meta.json", metaFor(pack, folder, ns, source));
            done.add("meta");
        }
        if (selected.contains(RAW_DATAPACK)) {
            done.add("datapack " + copyVerbatim(pack, namespaces, packDir) + " files");
        }
        return folder + " (" + String.join(", ", done) + ")";
    }

    // -- pack naming --------------------------------------------------------

    /**
     * The pack's primary namespace for naming: the non-minecraft namespace carrying the MOST content
     * ({@code blazeandcave} for BACAP, whose smaller {@code bacap_fanpacks} sub-namespace must not
     * win), else {@code minecraft} (vanilla, which only adds to its own namespace).
     */
    private static String primaryNamespace(PackResources pack) {
        String best = "minecraft";
        int bestCount = -1;
        for (String ns : pack.getNamespaces(PackType.SERVER_DATA)) {
            if (ns.equals("minecraft")) {
                continue;
            }
            int[] count = {0};
            pack.listResources(PackType.SERVER_DATA, ns, "", (id, supplier) -> count[0]++);
            if (count[0] > bestCount) {
                bestCount = count[0];
                best = ns;
            }
        }
        return best;
    }

    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+)+");

    /**
     * Version tag for a pack's folder: when Fabric knows the namespace it's the mod version
     * ({@code minecraft} -> the mc version, {@code twilightforest} -> that mod's version); otherwise a
     * version parsed from the pack id (a folder datapack like {@code …1.20.3.zip}); else the mc version.
     */
    private static String versionForPack(PackResources pack, String namespace) {
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(namespace);
        if (mod.isPresent()) {
            return sanitizeVersion(mod.get().getMetadata().getVersion().getFriendlyString());
        }
        Matcher matcher = VERSION.matcher(pack.packId());
        return matcher.find() ? sanitizeVersion(matcher.group()) : mcVersionTag();
    }

    /** "vanilla" / "mod" / "datapack" — what the pack came from, for meta.json and file selection. */
    private static String sourceLabel(PackResources pack, String ns) {
        if (pack.packId().equals("vanilla")) {
            return "vanilla";
        }
        return FabricLoader.getInstance().getModContainer(ns).isPresent() ? "mod" : "datapack";
    }

    /** Launched game version, e.g. {@code 26.1.2}. */
    private static String rawMcVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    /** Launched game version as an underscore tag, e.g. {@code 26_1_2}. */
    private static String mcVersionTag() {
        return sanitizeVersion(rawMcVersion());
    }

    /**
     * The base (vanilla) pack's content-pack folder, e.g. {@code minecraft_26_1_2} — matching the
     * {@code <namespace>_<version>} folder {@link #run} dumps vanilla into. Whole-game files that
     * aren't dumped per-source-pack ({@code entities.json}, the mob registry) live here so they land
     * in the same base pack as the rest of vanilla's files.
     */
    public static String basePackFolder() {
        return "minecraft_" + mcVersionTag();
    }

    private static String sanitizeVersion(String version) {
        return version.replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** First free {@code base}, {@code base_2}, … so two packs sharing a namespace don't collide. */
    private static String uniqueName(String base, Set<String> used) {
        String name = base;
        for (int i = 2; used.contains(name); i++) {
            name = base + "_" + i;
        }
        used.add(name);
        return name;
    }

    // -- raw datapack (verbatim copy into the pack's own content-pack folder) --

    /** Copy this pack's SERVER_DATA verbatim into {@code packDir/data/...} + a {@code pack.mcmeta},
     *  so the content-pack folder doubles as a loadable datapack. Returns the file count. */
    private static int copyVerbatim(PackResources pack, Set<String> namespaces, Path packDir) throws Exception {
        Path dataRoot = packDir.resolve("data");
        int[] written = {0};
        for (String namespace : namespaces) {
            pack.listResources(PackType.SERVER_DATA, namespace, "", (id, supplier) -> {
                Path target = dataRoot.resolve(id.getNamespace()).resolve(id.getPath());
                try (InputStream in = supplier.get()) {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    written[0]++;
                } catch (Exception ignored) {
                    // a resource that won't open contributes nothing (matches the json skip path)
                }
            });
        }
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
        JsonObject packMeta = new JsonObject();
        packMeta.addProperty("pack_format", format);
        packMeta.addProperty("description", packDir.getFileName() + " (AEM dump, MC " + rawMcVersion() + ")");
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", packMeta);
        Files.writeString(packDir.resolve("pack.mcmeta"), GSON.toJson(mcmeta));
        return written[0];
    }

    // -- advancements (data/<ns>/advancement[s]/*.json) ---------------------

    private static JsonObject advancements(ResourceManager rm) {
        Map<String, JsonObject> records = new TreeMap<>();
        // both the modern "advancement" and the legacy 1.20.x "advancements" folder (BACAP ships
        // plural), mirroring the committed extractor's data/<ns>/advancements? handling.
        for (String dir : new String[]{"advancement", "advancements"}) {
            for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, dir)) {
                String path = entry.getKey().getPath();
                int slash = path.indexOf('/');
                if (slash < 0 || !path.substring(0, slash).equals(dir)) {
                    continue;  // e.g. "advancements/..." caught by the "advancement" prefix scan
                }
                String rel = stripExt(path.substring(slash + 1));
                if (rel.startsWith("recipes/")) {
                    continue;  // recipe advancements are not checks
                }
                records.putIfAbsent(entry.getKey().getNamespace() + ":" + rel,   // 26.3 criteria shape undone
                        advancementRecord(LegacyLoot.convertAdvancement(entry.getValue(), rm), rel));
            }
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
        // A second requirement lives in the block's LOOT TABLE rather than a tag: some blocks only
        // yield a given item to a specific tool (grass and ferns drop seeds bare-handed but
        // themselves only to shears; leaves and cobweb want shears or Silk Touch; a mushroom block
        // yields itself only to Silk Touch). Recorded per dropped item so the grass gates on shears
        // while the wheat seeds off the same block stay free.
        Map<String, JsonObject> drops = new HashMap<>();
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "loot_table/blocks")) {
            String block = stripExt(entry.getKey().getPath()
                    .substring("loot_table/blocks/".length()));
            JsonObject needed = lootToolRequirements(LegacyLoot.convert(entry.getValue(), rm));   // 26.3 shape
            if (needed.size() > 0) {
                drops.put(block, needed);
            }
        }

        TreeSet<String> blocks = new TreeSet<>(pickaxe);
        blocks.addAll(drops.keySet());
        JsonObject out = new JsonObject();
        for (String block : blocks) {
            JsonObject record = new JsonObject();
            // Only a pickaxe-mineable block carries "needs"; a block listed purely for a per-item
            // tool (glow lichen) mines bare-handed and must not gain a pickaxe requirement.
            if (pickaxe.contains(block)) {
                String tier = needs.get(block);
                record.add("needs", tier == null ? JsonNull.INSTANCE
                        : new com.google.gson.JsonPrimitive(tier));
            }
            JsonObject needed = drops.get(block);
            if (needed != null) {
                record.add("drops", needed);
            }
            out.add(block, record);
        }
        return out;
    }

    // -- brewing (data/<ns>/recipe/brewing/*.json, 26.3+) ----------------------

    /**
     * {@code brewing.json} from the brewing recipes 26.3 introduced: potion type -> the reagents that
     * brew it from a water bottle, the same table that was hand-written while brewing was hard-coded.
     * Only drinkable-potion recipes count (splash/lingering add a trivial gunpowder/dragon's breath
     * step), and long_/strong_ variants are left to their base type, as before. Where several routes
     * need equally few reagents (slowness off swiftness or leaping; mundane off any of a dozen
     * items), the entry is {@code {"any_of": [[...], ...]}} instead of one list. Null when the pack
     * has no brewing recipes.
     */
    private static JsonObject brewing(ResourceManager rm) {
        List<String[]> edges = new ArrayList<>();   // {input type, output type, reagent}
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "recipe/brewing")) {
            JsonObject recipe = entry.getValue();
            JsonObject input = obj(recipe.get("input"));
            JsonObject output = obj(recipe.get("output"));
            if (!stripNs(string(input.get("item"), "")).equals("potion")
                    || !stripNs(string(output.get("id"), "")).equals("potion")) {
                continue;
            }
            String from = stripNs(string(obj(input.get("potion_contents")).get("potions"), ""));
            String to = stripNs(string(obj(obj(output.get("components")).get("minecraft:potion_contents")).get("potion"), ""));
            String reagent = stripNs(string(obj(recipe.get("reagent")).get("item"), ""));
            if (!from.isEmpty() && !to.isEmpty() && !reagent.isEmpty()) {
                edges.add(new String[]{from, to, reagent});
            }
        }
        if (edges.isEmpty()) {
            return null;
        }
        // Every minimal reagent set per potion type, relaxed to a fixed point (the graph is ~50 nodes).
        Map<String, Set<Set<String>>> routes = new TreeMap<>();
        routes.put("water", Set.of(Set.of()));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String[] edge : edges) {
                Set<Set<String>> sources = routes.get(edge[0]);
                if (sources == null) {
                    continue;
                }
                for (Set<String> source : List.copyOf(sources)) {
                    Set<String> route = new TreeSet<>(source);
                    route.add(edge[2]);
                    changed |= addMinimal(routes.computeIfAbsent(edge[1], key -> new HashSet<>()), route);
                }
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("_comment", "Dumped from the game's brewing recipes: potion type -> the reagents "
                + "that brew it from a water bottle (any_of: equally short alternatives). long_/strong_ "
                + "variants share their base type's reagents.");
        routes.forEach((type, sets) -> {
            if (type.equals("water") || type.startsWith("long_") || type.startsWith("strong_")) {
                return;
            }
            List<JsonArray> lists = new ArrayList<>();
            for (Set<String> set : sets) {
                JsonArray list = new JsonArray();
                new TreeSet<>(set).forEach(list::add);
                lists.add(list);
            }
            lists.sort(Comparator.comparing(JsonArray::toString));
            if (lists.size() == 1) {
                out.add(type, lists.get(0));
            } else {
                JsonArray any = new JsonArray();
                lists.forEach(any::add);
                JsonObject alternatives = new JsonObject();
                alternatives.add("any_of", any);
                out.add(type, alternatives);
            }
        });
        return out;
    }

    /** Keeps only the smallest routes: adds {@code route} if no kept one is smaller, dropping larger
     *  ones. True if the set changed. */
    private static boolean addMinimal(Set<Set<String>> kept, Set<String> route) {
        int best = kept.stream().mapToInt(Set::size).min().orElse(Integer.MAX_VALUE);
        if (route.size() > best || kept.contains(route)) {
            return false;
        }
        if (route.size() < best) {
            kept.clear();
        }
        kept.add(Set.copyOf(route));
        return true;
    }

    // -- block_biomes (data/<ns>/worldgen/{biome,placed_feature,configured_feature,noise_settings}) ---
    //
    // 26.3 reshaped this data: configured_feature/ became feature/ with the config fields at the top
    // level, block states may be a plain id string or {"id": ...} instead of {"Name": ...}, and
    // surface rules left noise_settings for material_rule/ + material_condition/ files referenced by
    // id (as block_state_provider/ files are). Both shapes are read, so older versions dump unchanged.

    /** Keys whose string values name blocks the feature TESTS for (where it may go or grow), not blocks
     *  it places. */
    private static final Set<String> NON_PLACING_KEYS = Set.of("type", "blocks", "block", "tag",
            "predicate_type", "fluids", "noise", "biome_is",
            "valid_blocks", "can_be_placed_on", "muddy_roots_in", "accepted_neighbors");

    /** Tree decorators place blocks by decorator TYPE; their config never spells the block out. */
    private static final Map<String, String> DECORATOR_BLOCKS = Map.of(
            "minecraft:cocoa", "cocoa",
            "minecraft:beehive", "bee_nest",
            "minecraft:trunk_vine", "vine",
            "minecraft:leave_vine", "vine",
            "minecraft:pale_moss", "pale_hanging_moss",
            "minecraft:creaking_heart", "creaking_heart");

    /** Feature types that place blocks in code, with no block state in their config. */
    private static final Map<String, List<String>> FEATURE_TYPE_BLOCKS = Map.of(
            "minecraft:bamboo", List.of("bamboo", "podzol"),
            "minecraft:sculk_patch", List.of("sculk", "sculk_vein", "sculk_catalyst", "sculk_shrieker"));

    /**
     * Which biomes each block generates in: {@code {"<block>": ["<biome>", ...]}}, the same file
     * {@code tools/build_block_biomes.py} writes. Read from the biomes' feature lists (placed feature ->
     * configured feature -> block states, nested features and tree decorators) and from the dimension
     * surface rules, whose {@code biome} conditions narrow where each surface block lands. The apworld
     * gates a block that only generates in rare biomes behind the Biome Finder.
     */
    private static JsonObject blockBiomes(ResourceManager rm) {
        Map<String, JsonObject> configured = idMap(rm, "worldgen/configured_feature");
        configured.putAll(idMap(rm, "worldgen/feature"));   // 26.3's name for it
        FeatureBlocks features = new FeatureBlocks(idMap(rm, "worldgen/placed_feature"), configured,
                idMap(rm, "worldgen/block_state_provider"));
        Map<String, TreeSet<String>> result = new TreeMap<>();
        TreeSet<String> allBiomes = new TreeSet<>();
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "worldgen/biome")) {
            String biome = stripExt(entry.getKey().getPath().substring("worldgen/biome/".length()));
            allBiomes.add(biome);
            for (JsonElement step : array(entry.getValue().get("features"))) {
                for (JsonElement feature : step.isJsonArray() ? array(step) : List.of(step)) {
                    for (String block : features.placed(feature)) {
                        result.computeIfAbsent(block, key -> new TreeSet<>()).add(biome);
                    }
                }
            }
        }
        Map<String, JsonObject> settings = idMap(rm, "worldgen/noise_settings");
        SurfaceRules rules = new SurfaceRules(idMap(rm, "worldgen/material_rule"),
                idMap(rm, "worldgen/material_condition"));
        for (String dimension : List.of("minecraft:overworld", "minecraft:nether", "minecraft:end")) {
            JsonObject body = settings.get(dimension);
            if (body != null) {
                JsonElement rule = body.has("surface_rule") ? body.get("surface_rule") : body.get("material_rule");
                surfaceBlocks(rule, allBiomes, result, rules);
            }
        }
        JsonObject out = new JsonObject();
        result.forEach((block, biomes) -> {
            if (!biomes.isEmpty() && !block.equals("air")) {
                JsonArray list = new JsonArray();
                biomes.forEach(list::add);
                out.add(block, list);
            }
        });
        return out;
    }

    /** {@code <prefix>/**.json} keyed by namespaced id ({@code minecraft:trees_jungle}). */
    private static Map<String, JsonObject> idMap(ResourceManager rm, String prefix) {
        Map<String, JsonObject> out = new HashMap<>();
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, prefix)) {
            Identifier id = entry.getKey();
            out.put(id.getNamespace() + ":" + stripExt(id.getPath().substring(prefix.length() + 1)), entry.getValue());
        }
        return out;
    }

    /** 26.3's surface rules and conditions, which rules may name by id instead of inlining. */
    private record SurfaceRules(Map<String, JsonObject> rules, Map<String, JsonObject> conditions) {
        JsonElement rule(JsonElement element) {
            return element != null && element.isJsonPrimitive() ? rules.get(namespaced(element.getAsString())) : element;
        }

        JsonObject condition(JsonElement element) {
            return element != null && element.isJsonPrimitive()
                    ? Optional.ofNullable(conditions.get(namespaced(element.getAsString()))).orElseGet(JsonObject::new)
                    : obj(element);
        }
    }

    /** Walks a surface rule tree, narrowing the biome set on each {@code biome} condition. A {@code not}
     *  around one is read as no narrowing: wider, never falsely rare. */
    private static void surfaceBlocks(JsonElement element, Set<String> biomes, Map<String, TreeSet<String>> out,
                                      SurfaceRules refs) {
        element = refs.rule(element);
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject rule = element.getAsJsonObject();
        switch (stripNs(string(rule.get("type"), ""))) {
            case "block" -> {
                String block = blockState(rule.get("result_state"));
                if (block != null && !block.isEmpty()) {
                    out.computeIfAbsent(block, key -> new TreeSet<>()).addAll(biomes);
                }
            }
            case "sequence" -> {
                for (JsonElement child : array(rule.get("sequence"))) {
                    surfaceBlocks(child, biomes, out, refs);
                }
            }
            case "condition" -> {
                JsonObject test = refs.condition(rule.get("if_true"));
                Set<String> narrowed = biomes;
                if (stripNs(string(test.get("type"), "")).equals("biome")) {
                    narrowed = new TreeSet<>();
                    // a list or a single id; both occur in 26.2 already, and a single id used to be
                    // read as no biome at all, which dropped every one-biome surface block
                    JsonElement biomeIs = test.get("biome_is");
                    for (JsonElement biome : biomeIs != null && biomeIs.isJsonPrimitive() ? List.of(biomeIs) : array(biomeIs)) {
                        String name = stripNs(biome.getAsString());
                        if (biomes.contains(name)) {
                            narrowed.add(name);
                        }
                    }
                }
                surfaceBlocks(rule.get("then_run"), narrowed, out, refs);
            }
            default -> { }
        }
    }

    /** Resolves feature references to the blocks they place. A placed feature and the configured
     *  feature it wraps often share an id ({@code trees_jungle}), so the two are cached apart: a placed
     *  feature's {@code feature} names a configured one, a configured feature's nested refs name placed
     *  ones. An empty set is stored before resolving, as the cycle guard. */
    private static final class FeatureBlocks {
        private final Map<String, JsonObject> placedDefs;
        private final Map<String, JsonObject> configuredDefs;
        private final Map<String, JsonObject> providerDefs;
        private final Map<String, Set<String>> placedCache = new HashMap<>();
        private final Map<String, Set<String>> configuredCache = new HashMap<>();

        FeatureBlocks(Map<String, JsonObject> placedDefs, Map<String, JsonObject> configuredDefs,
                      Map<String, JsonObject> providerDefs) {
            this.placedDefs = placedDefs;
            this.configuredDefs = configuredDefs;
            this.providerDefs = providerDefs;
        }

        Set<String> placed(JsonElement ref) {
            if (ref != null && ref.isJsonPrimitive()) {
                String id = namespaced(ref.getAsString());
                Set<String> cached = placedCache.get(id);
                if (cached == null) {
                    placedCache.put(id, Set.of());
                    JsonObject body = placedDefs.get(id);
                    cached = body != null ? placedBody(body) : configured(ref);
                    placedCache.put(id, cached);
                }
                return cached;
            }
            return ref != null && ref.isJsonObject() ? placedBody(ref.getAsJsonObject()) : Set.of();
        }

        private Set<String> placedBody(JsonObject body) {
            return body.has("placement") ? configured(body.get("feature")) : configured(body);
        }

        Set<String> configured(JsonElement ref) {
            if (ref != null && ref.isJsonPrimitive()) {
                String id = namespaced(ref.getAsString());
                Set<String> cached = configuredCache.get(id);
                if (cached == null) {
                    configuredCache.put(id, Set.of());
                    JsonObject body = configuredDefs.get(id);
                    cached = body != null ? configured(body) : Set.of();
                    configuredCache.put(id, cached);
                }
                return cached;
            }
            Set<String> found = new TreeSet<>();
            if (ref != null && ref.isJsonObject()) {
                JsonObject body = ref.getAsJsonObject();
                found.addAll(FEATURE_TYPE_BLOCKS.getOrDefault(string(body.get("type"), ""), List.of()));
                if (body.has("config")) {
                    walk(body.get("config"), found);
                } else {   // 26.3: the config fields sit beside "type"
                    JsonObject fields = body.deepCopy();
                    fields.remove("type");
                    walk(fields, found);
                }
            }
            return found;
        }

        private void walk(JsonElement element, Set<String> found) {
            if (element == null) {
                return;
            }
            if (element.isJsonArray()) {
                for (JsonElement item : element.getAsJsonArray()) {
                    walk(item, found);
                }
                return;
            }
            if (!element.isJsonObject()) {
                return;
            }
            JsonObject node = element.getAsJsonObject();
            String name = string(node.get("Name"), null);
            if (name != null) {
                found.add(stripNs(name));
            } else {
                String block = blockId(node.get("id"));   // 26.3: {"id": ..., "properties": ...}
                if (block != null) {
                    found.add(block);
                }
            }
            String decorator = DECORATOR_BLOCKS.get(string(node.get("type"), ""));
            if (decorator != null) {
                found.add(decorator);
            }
            for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
                String key = entry.getKey();
                if (key.equals("feature") || key.equals("features") || key.equals("default") || key.endsWith("_feature")) {
                    JsonElement value = entry.getValue();
                    for (JsonElement item : value.isJsonArray() ? value.getAsJsonArray() : List.of(value)) {
                        JsonElement target = item.isJsonObject() && item.getAsJsonObject().has("chance")
                                ? item.getAsJsonObject().get("feature") : item;
                        found.addAll(placed(target));
                    }
                } else if (NON_PLACING_KEYS.contains(key)) {
                    walk(entry.getValue(), found);
                } else {
                    // 26.3: a block state may be a bare id, alone or in a list (a flower patch's
                    // states, a geode's buds), or a block_state_provider named by id
                    JsonElement value = entry.getValue();
                    for (JsonElement item : value.isJsonArray() ? value.getAsJsonArray() : List.of(value)) {
                        if (item.isJsonPrimitive()) {
                            placedId(item, found);
                        } else {
                            walk(item, found);
                        }
                    }
                }
            }
        }

        private void placedId(JsonElement id, Set<String> found) {
            String block = blockId(id);
            if (block != null) {
                found.add(block);
                return;
            }
            JsonObject provider = providerDefs.get(namespaced(id.getAsString()));
            if (provider != null) {
                walk(provider, found);
            }
        }
    }

    /** A block state in either shape ({"Name"}/{"id"} object or bare id), as a bare block id; null if none. */
    private static String blockState(JsonElement element) {
        if (element != null && element.isJsonObject()) {
            JsonObject state = element.getAsJsonObject();
            return state.has("Name") ? stripNs(string(state.get("Name"), "")) : blockId(state.get("id"));
        }
        return blockId(element);
    }

    /** {@code element}'s string if it names a registered block (bare id), else null. Strings in worldgen
     *  JSON are also feature, provider, noise and template ids, so the registry decides. */
    private static String blockId(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        Identifier id = Identifier.tryParse(element.getAsString());
        return id != null && BuiltInRegistries.BLOCK.containsKey(id) ? stripNs(element.getAsString()) : null;
    }

    /** Per-item tool requirements for one block loot table: {@code {"<item>": ["shears","silk"]}}. */
    private static JsonObject lootToolRequirements(JsonObject table) {
        Map<String, Set<String>> found = new HashMap<>();
        Set<String> free = new HashSet<>();
        for (JsonElement pool : array(table.get("pools"))) {
            if (!pool.isJsonObject()) {
                continue;
            }
            List<JsonElement> inherited = new ArrayList<>();
            for (JsonElement condition : array(pool.getAsJsonObject().get("conditions"))) {
                inherited.add(condition);
            }
            walkLootEntries(pool.getAsJsonObject().get("entries"), inherited, found, free);
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Set<String>> entry : new TreeMap<>(found).entrySet()) {
            if (free.contains(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;  // a route with no tool gate wins: the item is free
            }
            JsonArray tools = new JsonArray();
            for (String tool : new TreeSet<>(entry.getValue())) {
                tools.add(tool);
            }
            out.add(entry.getKey(), tools);
        }
        return out;
    }

    /**
     * Collect {@code item -> tool requirement} from a loot-table subtree. {@code inherited} is the
     * conditions in scope: a pool's own conditions apply to every entry under it, and an
     * {@code alternatives} child carries its siblings' fallbacks (grass drops seeds when the shears
     * branch does not match).
     */
    private static void walkLootEntries(JsonElement node, List<JsonElement> inherited,
                                        Map<String, Set<String>> found, Set<String> free) {
        if (node == null || node.isJsonNull()) {
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                walkLootEntries(child, inherited, found, free);
            }
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        JsonObject obj = node.getAsJsonObject();
        List<JsonElement> conditions = new ArrayList<>(inherited);
        for (JsonElement condition : array(obj.get("conditions"))) {
            conditions.add(condition);
        }
        String type = obj.has("type") ? stripNs(obj.get("type").getAsString()) : "";
        if (!type.equals("item")) {
            walkLootEntries(obj.get("children"), conditions, found, free);
            return;
        }
        String item = obj.has("name") ? stripNs(obj.get("name").getAsString()) : "";
        if (item.isEmpty()) {
            return;
        }
        Set<String> required = null;
        for (JsonElement condition : conditions) {
            Set<String> tools = conditionTools(condition);
            if (tools == null) {
                continue;  // not a tool gate; says nothing
            }
            if (required == null) {
                required = new HashSet<>(tools);
            } else {
                required.retainAll(tools);  // both gates must hold
            }
        }
        if (required == null) {
            free.add(item);
            return;
        }
        found.computeIfAbsent(item, key -> new HashSet<>()).addAll(required);
    }

    /**
     * The tools a loot condition demands, or {@code null} if it is not a tool gate at all. Only
     * {@code match_tool} gates a drop on what you are holding; {@code survives_explosion},
     * {@code random_chance}, {@code block_state_property} and {@code table_bonus} say nothing about
     * the tool. {@code any_of} is the shears-or-Silk-Touch shape.
     */
    private static Set<String> conditionTools(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String kind = obj.has("condition") ? stripNs(obj.get("condition").getAsString()) : "";
        if (kind.equals("any_of")) {
            Set<String> anyOf = new HashSet<>();
            for (JsonElement term : array(obj.get("terms"))) {
                Set<String> tools = conditionTools(term);
                if (tools != null) {
                    anyOf.addAll(tools);
                }
            }
            return anyOf.isEmpty() ? null : anyOf;
        }
        if (!kind.equals("match_tool")) {
            return null;
        }
        JsonObject predicate = obj.has("predicate") && obj.get("predicate").isJsonObject()
                ? obj.getAsJsonObject("predicate") : new JsonObject();
        if (predicate.has("items") && predicate.get("items").toString().contains("shears")) {
            return Set.of("shears");
        }
        if (predicate.toString().contains("silk_touch")) {
            return Set.of("silk");
        }
        // A match_tool we cannot read still gates the drop on SOMETHING held; claiming "free" would
        // be the very bug this table exists to fix, so demand a tool nothing satisfies.
        return Set.of();
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

    /**
     * Structures are derived purely from the pack's own {@code worldgen/structure/*} definitions, so a
     * pack that adds none (BACAP) yields an empty list and a pack that adds structures (a mod) yields
     * its own — with zero curation. Each structure's display name is derived from its id, its region
     * from the worldgen json, and its natural-generation block palette from the best token-overlap NBT
     * template folder (see {@link #bestPalette}). Non-worldgen "structures" like {@code monster_room}
     * (Dungeon) and {@code desert_well} have no worldgen definition and are therefore absent.
     * Order is the game_id sort order (a {@link TreeMap}), the stable Structure-Unlock item id.
     */
    private static JsonArray structures(ResourceManager rm) {
        Map<String, JsonObject> structDefs = new TreeMap<>();   // game_id (namespaced if not vanilla) -> json
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "worldgen/structure")) {
            String rel = stripExt(entry.getKey().getPath().substring("worldgen/structure/".length()));
            String namespace = entry.getKey().getNamespace();
            String gameId = namespace.equals("minecraft") ? rel : namespace + ":" + rel;
            structDefs.put(gameId, entry.getValue());
        }
        Map<String, JsonArray> biomeTags = new HashMap<>();     // bare tag path -> raw values
        for (Map.Entry<Identifier, JsonObject> entry : jsonResources(rm, "tags/worldgen/biome")) {
            JsonElement values = entry.getValue().get("values");
            if (values != null && values.isJsonArray()) {
                biomeTags.put(stripExt(entry.getKey().getPath().substring("tags/worldgen/biome/".length())),
                        values.getAsJsonArray());
            }
        }
        Map<String, TreeSet<String>> palettes = structurePalettes(rm);  // NBT folder -> merged palette

        // game_id -> record, so the worldgen structures and the curated feature-structures emit in one
        // stable game_id-sorted order (the Structure-Unlock id order, which the apworld keys on).
        Map<String, JsonObject> records = new TreeMap<>();
        for (Map.Entry<String, JsonObject> entry : structDefs.entrySet()) {
            String gameId = entry.getKey();
            records.put(gameId, structureRecord(prettifyId(gameId), gameId, entry.getValue(),
                    biomeTags, bestPalette(gameId, palettes)));
        }
        // Curated feature-structures only when THIS pack actually defines the placed feature, so the
        // vanilla pack (worldgen/placed_feature/monster_room|desert_well) gets them but a structure-less
        // datapack like BACAP does not.
        Set<String> placedFeatures = new TreeSet<>();
        for (Identifier id : rm.listResources("worldgen/placed_feature",
                p -> p.getPath().endsWith(".json")).keySet()) {
            String rel = stripExt(id.getPath().substring("worldgen/placed_feature/".length()));
            placedFeatures.add(id.getNamespace().equals("minecraft") ? rel : id.getNamespace() + ":" + rel);
        }
        for (String[] feature : FEATURE_STRUCTURES) {
            if (placedFeatures.contains(feature[0])) {
                records.putIfAbsent(feature[0], featureStructureRecord(feature[0], feature[1],
                        bestPalette(feature[0], palettes)));
            }
        }
        JsonArray table = new JsonArray();
        records.values().forEach(table::add);
        return table;
    }

    /**
     * Worldgen FEATURES (placed via PlacedFeature during biome decoration, NOT the worldgen/structure
     * registry — e.g. {@code monster_room} (Dungeon), {@code desert_well}) that the AP structure-lock
     * still gates (see PlacedFeatureMixin). They have no worldgen/structure def to derive region/name
     * from, so {game_id, region} is curated here; the apworld treats them as ordinary structures.
     */
    private static final String[][] FEATURE_STRUCTURES = {
            {"monster_room", "Overworld"},
            {"desert_well", "Overworld"},
    };

    private static JsonObject featureStructureRecord(String gameId, String region, TreeSet<String> palette) {
        JsonObject record = new JsonObject();
        record.addProperty("name", prettifyId(gameId));
        record.addProperty("game_id", gameId);
        record.addProperty("region", region);
        JsonArray blocks = new JsonArray();
        if (palette != null) {
            palette.forEach(blocks::add);
        }
        record.add("blocks", blocks);
        return record;
    }

    private static JsonObject structureRecord(String name, String gameId, JsonObject def,
                                              Map<String, JsonArray> biomeTags, TreeSet<String> palette) {
        JsonObject record = new JsonObject();
        record.addProperty("name", name);
        record.addProperty("game_id", gameId);
        record.addProperty("region", region(def, biomeTags));
        JsonArray blocks = new JsonArray();
        if (palette != null) {
            palette.forEach(blocks::add);
        }
        record.add("blocks", blocks);
        return record;
    }

    /** "twilightforest:hollow_hill" / "trail_ruins" -> "Hollow Hill" / "Trail Ruins". */
    private static String prettifyId(String gameId) {
        StringBuilder name = new StringBuilder();
        for (String word : stripNs(gameId).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
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

    /**
     * Merge every structure template into its NBT folder, keyed by the top-level folder under
     * {@code structure/} (e.g. {@code ancient_city}, {@code nether_fossils}, {@code village},
     * {@code ruined_portal}). A template sitting directly under {@code structure/} keys on its own
     * file name. {@link #bestPalette} later matches each structure id to one of these folders.
     */
    private static boolean paletteFailureLogged;

    private static Map<String, TreeSet<String>> structurePalettes(ResourceManager rm) {
        Map<String, TreeSet<String>> palettes = new HashMap<>();
        Map<Identifier, Resource> nbts = rm.listResources("structure", id -> id.getPath().endsWith(".nbt"));
        paletteFailureLogged = false;
        for (Map.Entry<Identifier, Resource> entry : nbts.entrySet()) {
            String rel = entry.getKey().getPath().substring("structure/".length());
            int slash = rel.indexOf('/');
            String folder = slash < 0 ? stripExt(rel) : rel.substring(0, slash);
            palettes.computeIfAbsent(folder, k -> new TreeSet<>()).addAll(paletteBlocks(entry.getValue()));
        }
        return palettes;
    }

    /**
     * Palette of the NBT folder whose name shares the most tokens with this structure's id (both
     * split on {@code _}), so every structure — vanilla or modded, jigsaw or single-piece — gets its
     * template blocks with zero curation. Score = 2 x exact-token overlap + 1 x stem-only overlap, so
     * an exact match wins over a mere stem match (underwater_ruin beats ruined_portal for
     * ocean_ruin_cold; nether_fossils beats fossil for nether_fossil). Needs score >= 1, else no
     * palette ({@code null}). Folders are scanned in sorted order so ties resolve deterministically.
     */
    private static TreeSet<String> bestPalette(String gameId, Map<String, TreeSet<String>> palettes) {
        List<String> idTokens = tokens(stripNs(gameId));
        TreeSet<String> best = null;
        int bestScore = 0;
        int bestUnmatched = Integer.MAX_VALUE;
        for (String folder : new TreeSet<>(palettes.keySet())) {
            List<String> folderTokens = tokens(folder);
            int score = overlapScore(idTokens, folderTokens);
            // A tie goes to the folder with fewer words the id doesn't share: village_desert is the
            // village/ templates, not 26.3's desert_well/ (both share one word with it).
            int unmatched = unmatchedTokens(idTokens, folderTokens);
            if (score > bestScore || (score == bestScore && score > 0 && unmatched < bestUnmatched)) {
                bestScore = score;
                bestUnmatched = unmatched;
                best = palettes.get(folder);
            }
        }
        return best;
    }

    /** {@code 2 x} exact token overlap {@code + 1 x} stem-only overlap (matches under {@link #stem}
     *  that are not already exact), counted over the folder's tokens. */
    private static int overlapScore(List<String> idTokens, List<String> folderTokens) {
        Set<String> ids = new HashSet<>(idTokens);
        Set<String> idStems = new HashSet<>();
        for (String token : idTokens) {
            idStems.add(stem(token));
        }
        int exact = 0;
        int stemOnly = 0;
        for (String token : folderTokens) {
            if (ids.contains(token)) {
                exact++;
            } else if (idStems.contains(stem(token))) {
                stemOnly++;
            }
        }
        return 2 * exact + stemOnly;
    }

    /** Folder tokens that match the id neither exactly nor by {@link #stem}. */
    private static int unmatchedTokens(List<String> idTokens, List<String> folderTokens) {
        Set<String> idStems = new HashSet<>();
        for (String token : idTokens) {
            idStems.add(stem(token));
        }
        int unmatched = 0;
        for (String token : folderTokens) {
            if (!idTokens.contains(token) && !idStems.contains(stem(token))) {
                unmatched++;
            }
        }
        return unmatched;
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String token : text.split("_")) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    /** Collapse a simple plural / past-tense suffix so {@code ruined}/{@code ruins} stem to
     *  {@code ruin} and {@code fossils} to {@code fossil} (length-guarded to spare short tokens). */
    private static String stem(String token) {
        if (token.endsWith("ed") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && token.length() > 3) {
            return token.substring(0, token.length() - 1);
        }
        return token;
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
                        // 26.3 renamed a palette entry's block key from "Name" to "id"
                        String name = state.getStringOr("Name", state.getStringOr("id", ""));
                        if (!name.isEmpty()) {
                            blocks.add(stripNs(name));
                        }
                    });
                }
            }
        } catch (Exception exception) {
            // a template that won't parse contributes no palette; say so once per dump, since when
            // EVERY template fails (a changed NBT API) the only symptom is empty structure palettes
            if (!paletteFailureLogged) {
                paletteFailureLogged = true;
                AEM.LOGGER.warn("Could not read structure template palettes (first failure)", exception);
            }
        }
        return blocks;
    }

    // -- meta ---------------------------------------------------------------

    private static JsonObject metaFor(PackResources pack, String name, String namespace, String source) {
        String version = rawMcVersion();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        meta.addProperty("source", source);
        meta.addProperty("namespace", namespace);
        meta.addProperty("mc_version", version);
        meta.addProperty("description", name + " — dumped from the running game (MC " + version + ").");
        if (!"vanilla".equals(source)) {
            // An OVERLAY pack is checked on the player's machine before the world loads
            // (ContentVerification, fed by data.py's OVERLAY_REQUIREMENTS -> slot_data
            // required_content): `match` is the substring that finds it among the installed packs,
            // `content_version` is the version the seed was generated against, and `display_name` is
            // what the mismatch message calls it. Leaving them out is not neutral — an absent
            // content_version downgrades the check to "installed at all", which lets a player load a
            // seed against a different BACAP and desync every advancement location. They used to be
            // hand-added to the dumped file for exactly that reason.
            String contentVersion = contentVersionFor(pack, namespace);
            if (!contentVersion.isEmpty()) {
                meta.addProperty("content_version", contentVersion);
            }
            meta.addProperty("display_name", displayNameFor(pack, namespace));
            meta.addProperty("match", namespace.toLowerCase(Locale.ROOT));
        }
        return meta;
    }

    /**
     * The pack's OWN version, dotted as it is written — a mod's Fabric version, else the version in
     * the pack id (BACAP ships its version only in its filename, "…Pack 1.20.3.zip"). Empty when
     * there is none to pin: ContentVerification reads that as "any installed copy will do", which is
     * the honest answer rather than the Minecraft version {@link #versionForPack} falls back to for
     * folder naming.
     */
    private static String contentVersionFor(PackResources pack, String namespace) {
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(namespace);
        if (mod.isPresent()) {
            return mod.get().getMetadata().getVersion().getFriendlyString();
        }
        Matcher matcher = VERSION.matcher(pack.packId());
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * What to call the pack in a mismatch message: a mod's declared name, else its pack id with any
     * source prefix, file extension and trailing version dropped ("file/BlazeandCave's Advancements
     * Pack 1.20.3.zip" -> "BlazeandCave's Advancements Pack"), else the namespace.
     */
    private static String displayNameFor(PackResources pack, String namespace) {
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(namespace);
        if (mod.isPresent()) {
            return mod.get().getMetadata().getName();
        }
        String id = pack.packId();
        id = id.substring(id.lastIndexOf('/') + 1);  // a zip datapack's id is "file/<name>.zip"
        int dot = id.lastIndexOf('.');
        if (dot > 0 && id.length() - dot <= 5) {
            id = id.substring(0, dot);  // ".zip"
        }
        Matcher matcher = VERSION.matcher(id);
        if (matcher.find()) {
            id = id.substring(0, matcher.start());
        }
        id = id.trim();
        return id.isEmpty() ? namespace : id;
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
