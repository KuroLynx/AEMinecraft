package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
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
            "advancements", "structures", "acquisition", "block_mining", "tags", "meta");

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
            done.add("acquisition " + writeCount(packDir, "acquisition.json", AcquisitionDump.build(rm)));
        }
        if (selected.contains("block_mining")) {
            done.add("block_mining " + writeCount(packDir, "block_mining.json", blockMining(rm)));
        }
        if (selected.contains("tags")) {
            write(packDir, "tags.json", tags(rm));
            done.add("tags");
        }
        if (selected.contains("meta")) {
            write(packDir, "meta.json", metaFor(folder, ns, source));
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
                records.putIfAbsent(entry.getKey().getNamespace() + ":" + rel,
                        advancementRecord(entry.getValue(), rel));
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
    private static Map<String, TreeSet<String>> structurePalettes(ResourceManager rm) {
        Map<String, TreeSet<String>> palettes = new HashMap<>();
        Map<Identifier, Resource> nbts = rm.listResources("structure", id -> id.getPath().endsWith(".nbt"));
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
        for (String folder : new TreeSet<>(palettes.keySet())) {
            int score = overlapScore(idTokens, tokens(folder));
            if (score > bestScore) {
                bestScore = score;
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

    private static JsonObject metaFor(String name, String namespace, String source) {
        String version = rawMcVersion();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        meta.addProperty("source", source);
        meta.addProperty("namespace", namespace);
        meta.addProperty("mc_version", version);
        meta.addProperty("description", name + " — dumped from the running game (MC " + version + ").");
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
