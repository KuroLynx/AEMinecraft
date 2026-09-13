package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds {@code acquisition.json} from the running game's raw data resources — the in-game port of
 * {@code tools/build_acquisition.py}. It reads the SAME files the offline tool reads from the jar
 * (recipes, loot tables, villager trades, item tags) via the server {@link ResourceManager} and
 * applies the identical processing, so the apworld ingests either source the same way. Resources are
 * processed id-sorted, so the output is deterministic.
 *
 * <p>Per-item record (only non-empty keys), {@code <ing> = {"item":x} | {"tag":x} | {"any_of":[...]}}:
 * {@code advancements / recipes / drops / mining / silk_mining / structures / archaeology / trades /
 * breeding / gameplay},
 * plus {@code chances} — {@code {"<kind>/<source>": p}} for the sources that are not a sure thing.
 *
 * <p>The {@code advancements} source (item -> advancement game_ids that grant it as a completion reward)
 * is built ONLY for the BlazeandCave's Advancements Pack ({@link #BACAP_NAMESPACE}), whose advancements
 * hand out items via reward functions ({@code rewards.function}). Vanilla advancement rewards are not
 * modeled this way.
 */
final class AcquisitionDump {
    private AcquisitionDump() {}

    /** The datapack whose advancement rewards are mined into the {@code advancements} source. */
    private static final String BACAP_NAMESPACE = "blazeandcave";

    private static final List<String> ENCHANT_FUNCS = List.of(
            "enchant_randomly", "enchant_with_levels", "set_enchantments");
    // Structures are referenced by game_id (the structures.json key); display names are derived in
    // the apworld via prettify(game_id). monster_room (Dungeon) and desert_well are worldgen FEATURES,
    // not registry structures, but the AP structure-lock gates them too (see PlacedFeatureMixin) so
    // they keep a game_id here and an entry in structures.json.
    private static final List<String> VILLAGE_BIOMES = List.of(
            "village_desert", "village_plains", "village_savanna", "village_snowy", "village_taiga");
    private static final Map<String, String[]> CHEST_STRUCTURE = Map.ofEntries(
            Map.entry("abandoned_mineshaft", new String[]{"mineshaft"}),
            Map.entry("buried_treasure", new String[]{"buried_treasure"}),
            Map.entry("desert_pyramid", new String[]{"desert_pyramid"}),
            Map.entry("desert_well", new String[]{"desert_well"}),
            Map.entry("end_city_treasure", new String[]{"end_city"}),
            Map.entry("igloo_chest", new String[]{"igloo"}),
            Map.entry("jungle_temple", new String[]{"jungle_pyramid"}),
            Map.entry("jungle_temple_dispenser", new String[]{"jungle_pyramid"}),
            Map.entry("nether_bridge", new String[]{"fortress"}),
            Map.entry("pillager_outpost", new String[]{"pillager_outpost"}),
            Map.entry("ruined_portal", new String[]{"ruined_portal"}),
            Map.entry("simple_dungeon", new String[]{"monster_room"}),
            Map.entry("woodland_mansion", new String[]{"mansion"}),
            Map.entry("ocean_ruin_cold", new String[]{"ocean_ruin_cold"}),
            Map.entry("ocean_ruin_warm", new String[]{"ocean_ruin_warm"}),
            Map.entry("trail_ruins_common", new String[]{"trail_ruins"}),
            Map.entry("trail_ruins_rare", new String[]{"trail_ruins"}));
    // Drops hard-coded in entity code (no loot table): item -> mob loot-table basename(s).
    private static final Map<String, String[]> HARDCODED_DROPS = Map.of("nether_star", new String[]{"wither"});
    private static final String TRADE_SEP = "";  // joins (profession, file); sorts below text

    // -- accumulators (insertion-ordered recipe lists; sorted member sets) ----
    private final Map<String, List<JsonObject>> recipes = new TreeMap<>();
    private final Map<String, TreeSet<String>> drops = new TreeMap<>();
    private final Map<String, TreeSet<String>> mining = new TreeMap<>();
    private final Map<String, TreeSet<String>> silkMining = new TreeMap<>();
    private final Map<String, TreeSet<String>> structures = new TreeMap<>();
    /** Brushed out of suspicious sand/gravel — a different gate from a chest, so a separate key. */
    private final Map<String, TreeSet<String>> archaeology = new TreeMap<>();
    private final Map<String, TreeSet<String>> trades = new TreeMap<>();  // "proffile"
    private final Map<String, TreeSet<String>> breeding = new TreeMap<>();
    private final Map<String, TreeSet<String>> gameplay = new TreeMap<>();
    private final Map<String, TreeSet<String>> advancements = new TreeMap<>();  // item -> adv game_ids
    private final Map<String, JsonArray> itemTags = new TreeMap<>();  // bare path -> raw values
    // item -> {"<kind>/<source>": best-case chance}, recorded only for sources that are NOT certain.
    // Feeds the glitch partition in the apworld (logic/acquisition.py): an unreliable ALTERNATE route
    // — a 5.5% skull, a 2% barter — is demoted out of strict logic when a dependable source survives,
    // while a sole source is left alone however bad the odds. Best case throughout: the question is
    // "can a player count on this", so looting is assumed maxed and a range of rolls takes its top.
    private final Map<String, Map<String, Double>> chances = new TreeMap<>();

    static JsonObject build(ResourceManager rm, String primaryNamespace) {
        AcquisitionDump dump = new AcquisitionDump();
        // Tags first so recipe ingredient expansion can resolve them, then recipes / loot / trades.
        dump.forEachJson(rm, "tags/item", dump::onItemTag);
        dump.forEachJson(rm, "tags/items", dump::onItemTag);
        dump.forEachJson(rm, "recipe", (path, json) -> dump.onRecipe(json));
        dump.forEachJson(rm, "recipes", (path, json) -> dump.onRecipe(json));
        dump.forEachJson(rm, "loot_table", dump::onLoot);
        dump.forEachJson(rm, "loot_tables", dump::onLoot);
        dump.forEachJson(rm, "villager_trade", dump::onTrade);
        if (BACAP_NAMESPACE.equals(primaryNamespace)) {
            dump.collectAdvancementRewards(rm);
        }
        return dump.table();
    }

    private interface JsonSink {
        void accept(String path, JsonObject json);
    }

    /** Parse every {@code <prefix>/**.json} resource (id-sorted, exceptions skipped) and hand it off. */
    private void forEachJson(ResourceManager rm, String prefix, JsonSink sink) {
        Map<Identifier, Resource> found = rm.listResources(prefix, id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(found.entrySet());
        sorted.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
        for (Map.Entry<Identifier, Resource> entry : sorted) {
            try (Reader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) {
                    sink.accept(entry.getKey().getPath(), parsed.getAsJsonObject());
                }
            } catch (Exception ignored) {
                // malformed / unreadable resource — skip, like the offline tool
            }
        }
    }

    // -- recipes ------------------------------------------------------------

    private void onRecipe(JsonObject recipe) {
        JsonElement result = recipe.get("result");
        String item = null;
        if (result != null && result.isJsonPrimitive()) {
            item = result.getAsString();
        } else if (result != null && result.isJsonObject()) {
            JsonObject obj = result.getAsJsonObject();
            item = string(obj.get("item"), string(obj.get("id"), null));
        }
        if (item == null || item.isEmpty()) {
            return;
        }
        List<JsonObject> ingredients = recipeIngredients(recipe);
        if (ingredients.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        ingredients.forEach(array::add);
        JsonObject record = new JsonObject();
        record.add("ingredients", array);                          // alphabetical: ingredients < station
        record.addProperty("station", stripNs(string(recipe.get("type"), "")));
        recipes.computeIfAbsent(stripNs(item), k -> new ArrayList<>()).add(record);
    }

    /** Distinct input ingredients of a recipe across the formats vanilla uses. */
    private static List<JsonObject> recipeIngredients(JsonObject recipe) {
        List<JsonObject> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (recipe.has("key") && recipe.get("key").isJsonObject()) {        // crafting_shaped
            for (Map.Entry<String, JsonElement> slot : recipe.getAsJsonObject("key").entrySet()) {
                addIngredient(out, seen, slot.getValue());
            }
        }
        if (recipe.has("ingredients") && recipe.get("ingredients").isJsonArray()) {  // shapeless
            for (JsonElement spec : recipe.getAsJsonArray("ingredients")) {
                addIngredient(out, seen, spec);
            }
        }
        for (String field : List.of("ingredient", "base", "addition", "template", "input", "material")) {
            if (recipe.has(field)) {
                addIngredient(out, seen, recipe.get(field));
            }
        }
        return out;
    }

    private static void addIngredient(List<JsonObject> out, Set<String> seen, JsonElement spec) {
        JsonObject ing = ingredient(spec);
        String key = ing.toString();
        if (seen.add(key)) {
            out.add(ing);
        }
    }

    /** Normalise a recipe ingredient to {"item":x} | {"tag":x} | {"any_of":[...]}. */
    private static JsonObject ingredient(JsonElement spec) {
        JsonObject out = new JsonObject();
        if (spec.isJsonArray()) {
            JsonArray any = new JsonArray();
            for (JsonElement sub : spec.getAsJsonArray()) {
                any.add(ingredient(sub));
            }
            out.add("any_of", any);
            return out;
        }
        if (spec.isJsonObject()) {
            JsonObject obj = spec.getAsJsonObject();
            if (obj.has("item")) {
                return ingredient(obj.get("item"));
            }
            if (obj.has("id")) {
                return ingredient(obj.get("id"));
            }
            if (obj.has("tag")) {
                out.addProperty("tag", stripNs(obj.get("tag").getAsString()));
                return out;
            }
            out.addProperty("item", "?");
            return out;
        }
        String text = spec.getAsString();
        if (text.startsWith("#")) {
            out.addProperty("tag", stripNs(text.substring(1)));
        } else {
            out.addProperty("item", stripNs(text));
        }
        return out;
    }

    // -- loot ---------------------------------------------------------------

    private void onLoot(String path, JsonObject loot) {
        // path = "loot_table/<category>/<rel>.json" (or "loot_tables/..."); strip the prefix.
        int slash = path.indexOf('/');
        String afterPrefix = path.substring(slash + 1);                 // "<category>/<rel>.json"
        int catSlash = afterPrefix.indexOf('/');
        if (catSlash < 0) {
            return;
        }
        String category = afterPrefix.substring(0, catSlash);
        String rel = afterPrefix.substring(catSlash + 1, afterPrefix.length() - ".json".length());
        String fileName = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;

        if (category.equals("blocks")) {
            TreeSet<String> free = new TreeSet<>();
            TreeSet<String> silk = new TreeSet<>();
            for (JsonElement pool : array(loot.get("pools"))) {
                TreeSet<String> target = poolNeedsSilk(pool.getAsJsonObject()) ? silk : free;
                for (JsonElement entry : array(pool.getAsJsonObject().get("entries"))) {
                    target.addAll(lootItems(entry));
                }
            }
            for (String item : free) {
                mining.computeIfAbsent(item, k -> new TreeSet<>()).add(fileName);
            }
            for (String item : silk) {
                if (!free.contains(item)) {
                    silkMining.computeIfAbsent(item, k -> new TreeSet<>()).add(fileName);
                }
            }
            // A block's own drop is certain and records nothing; its incidental yields do (an apple
            // off oak leaves, a sapling), and those are exactly the flimsy alternates to spot.
            recordChances(loot.get("pools"), "mining", fileName);
            recordChances(loot.get("pools"), "silk_mining", fileName);
            return;
        }
        TreeSet<String> items = new TreeSet<>();
        for (JsonElement pool : array(loot.get("pools"))) {
            for (JsonElement entry : array(pool.getAsJsonObject().get("entries"))) {
                items.addAll(lootItems(entry));
            }
        }
        for (String item : items) {
            switch (category) {
                case "entities" -> drops.computeIfAbsent(item, k -> new TreeSet<>()).add(fileName);
                case "archaeology" -> {
                    // Suspicious sand/gravel: the loot comes out with a BRUSH, and breaking the
                    // block destroys it. Folding it in with the chests made a sherd read as
                    // "reach the structure and open a container", which is not how you get one.
                    for (String struct : structuresFor(rel)) {
                        archaeology.computeIfAbsent(item, k -> new TreeSet<>()).add(struct);
                    }
                }
                case "chests", "dispensers", "spawners" -> {
                    for (String struct : structuresFor(rel)) {
                        structures.computeIfAbsent(item, k -> new TreeSet<>()).add(struct);
                    }
                }
                case "shearing" -> { /* wool/etc from shearing a mob — covered by the mob */ }
                default -> gameplay.computeIfAbsent(item, k -> new TreeSet<>()).add(fileName);
            }
        }
        switch (category) {
            case "entities" -> recordChances(loot.get("pools"), "drops", fileName);
            case "archaeology" -> recordChances(loot.get("pools"), "archaeology", structuresFor(rel));
            case "chests", "dispensers", "spawners" ->
                    recordChances(loot.get("pools"), "structures", structuresFor(rel));
            case "shearing" -> { /* not a source, so no chance either */ }
            default -> recordChances(loot.get("pools"), "gameplay", fileName);
        }
    }

    /** Item ids a loot entry (and its nested children/entries/pools) names. */
    private List<String> lootItems(JsonElement element) {
        List<String> items = new ArrayList<>();
        if (element == null || !element.isJsonObject()) {
            return items;
        }
        JsonObject entry = element.getAsJsonObject();
        for (String key : List.of("value", "name", "id")) {
            if (entry.has(key) && entry.get(key).isJsonPrimitive()) {
                String leaf = stripNs(entry.get(key).getAsString());
                if (leaf.contains("/")) {
                    break;  // a referenced loot-table id (e.g. charged_creeper/creeper), not an item
                }
                items.add(leaf);
                if (leaf.equals("book") && isEnchanted(entry)) {
                    items.add("enchanted_book");
                }
                break;
            }
        }
        for (String key : List.of("children", "entries", "pools")) {
            for (JsonElement sub : array(entry.get(key))) {
                items.addAll(lootItems(sub));
            }
        }
        return items;
    }

    // -- drop chance --------------------------------------------------------
    /** Conditions that make a pool or entry roll only some of the time. */
    private static final List<String> CHANCE_CONDITIONS =
            List.of("random_chance", "random_chance_with_enchanted_bonus");
    /** Looting III — the ceiling of every drop-rate enchantment vanilla scales a loot number by. */
    private static final int MAX_ENCHANT_LEVEL = 3;

    /** Best case of a loot number: a plain number, a uniform range, or a number provider. */
    private static double number(JsonElement value, double fallback) {
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (value.isJsonPrimitive()) {
            try {
                return value.getAsDouble();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            // A `linear` provider scales with an enchantment level: the wither skeleton skull is
            // base 0.035 + 0.01 per level above the first. Read at the top level the enchantment
            // allows, the same best case the rest of this measure uses.
            if (object.has("base") && object.has("per_level_above_first")) {
                return number(object.get("base"), fallback)
                        + number(object.get("per_level_above_first"), 0.0) * (MAX_ENCHANT_LEVEL - 1);
            }
            for (String key : List.of("max", "value", "n", "base")) {
                if (object.has(key)) {
                    return number(object.get(key), fallback);
                }
            }
        }
        return fallback;
    }

    /** Product of the explicit random-chance conditions on a pool or entry (1.0 when none). */
    private static double conditionChance(JsonObject holder) {
        double chance = 1.0;
        for (JsonElement element : array(holder.get("conditions"))) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject condition = element.getAsJsonObject();
            if (!CHANCE_CONDITIONS.contains(stripNs(string(condition.get("condition"), "")))) {
                continue;
            }
            for (String key : List.of("enchanted_chance", "chance", "unenchanted_chance")) {
                if (condition.has(key)) {
                    chance *= number(condition.get(key), 1.0);
                    break;
                }
            }
        }
        return chance;
    }

    /**
     * Chance a {@code set_count} function actually yields at least one item.
     *
     * <p>Mob tables express rarity through the count, not a condition: a wither skeleton's coal is
     * {@code set_count uniform[-1, 1]}, i.e. nothing two rolls out of three. Ranges starting at 1 or
     * above are certain. A looting {@code enchanted_count_increase} raises the ceiling and counts at
     * its maximum, for the same best-case reason as the chance conditions.
     */
    private static double countChance(JsonObject entry) {
        double bonus = 0.0;
        for (JsonElement element : array(entry.get("functions"))) {
            if (element.isJsonObject()
                    && stripNs(string(element.getAsJsonObject().get("function"), ""))
                            .equals("enchanted_count_increase")) {
                bonus = Math.max(bonus, number(element.getAsJsonObject().get("count"), 0.0));
            }
        }
        double chance = 1.0;
        for (JsonElement element : array(entry.get("functions"))) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject function = element.getAsJsonObject();
            if (!stripNs(string(function.get("function"), "")).equals("set_count")) {
                continue;
            }
            JsonElement countElement = function.get("count");
            if (countElement == null || !countElement.isJsonObject()
                    || !countElement.getAsJsonObject().has("min")) {
                continue;
            }
            JsonObject count = countElement.getAsJsonObject();
            double low = number(count.get("min"), 1.0);
            double high = number(count.get("max"), low) + bonus;
            if (low >= 1.0 || high < low) {
                continue;
            }
            // Inclusive integer draw: how many of the possible values land on 1 or more.
            double outcomes = high - low + 1.0;
            double favourable = high - Math.max(low, 1.0) + 1.0;
            chance *= outcomes > 0 ? Math.max(favourable, 0.0) / outcomes : 0.0;
        }
        return chance;
    }

    /**
     * Accumulates {@code item -> per-roll chance} for one entry, {@code share} being how often this
     * entry is the one picked (its weight fraction) times any random-chance condition on it.
     *
     * <p>Composite entries (alternatives / group / sequence) pass their share down to every child
     * rather than splitting it: only {@code alternatives} picks a single child, and which one wins
     * depends on run-time predicates the table can't be read for. Crediting each child with the full
     * share is the best case, the direction this whole measure leans.
     */
    private void entryChances(JsonElement element, double share, Map<String, Double> out) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject entry = element.getAsJsonObject();
        double entryShare = share * conditionChance(entry);
        for (String key : List.of("children", "entries")) {
            if (entry.has(key) && entry.get(key).isJsonArray()) {
                for (JsonElement child : array(entry.get(key))) {
                    entryChances(child, entryShare, out);
                }
                return;
            }
        }
        entryShare *= countChance(entry);
        for (String item : lootItems(entry)) {
            out.merge(item, entryShare, Math::max);
        }
    }

    /** {@code item -> chance of getting at least one} from a single pool, across all its rolls. */
    private Map<String, Double> poolChances(JsonObject pool) {
        List<JsonElement> entries = new ArrayList<>();
        for (JsonElement entry : array(pool.get("entries"))) {
            entries.add(entry);
        }
        double total = 0.0;
        List<Double> weights = new ArrayList<>();
        for (JsonElement entry : entries) {
            double weight = entry.isJsonObject()
                    ? number(entry.getAsJsonObject().get("weight"), 1.0) : 1.0;
            weights.add(weight);
            if (weight > 0) {
                total += weight;
            }
        }
        if (total <= 0) {
            total = 1.0;
        }
        double rolls = Math.max(number(pool.get("rolls"), 1.0), 1.0);
        double poolChance = conditionChance(pool);
        Map<String, Double> out = new TreeMap<>();
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Double> perRoll = new TreeMap<>();
            entryChances(entries.get(index), (Math.max(weights.get(index), 0.0) / total) * poolChance,
                    perRoll);
            for (Map.Entry<String, Double> hit : perRoll.entrySet()) {
                // At least one hit across `rolls` independent draws.
                double combined = 1.0 - Math.pow(1.0 - Math.min(hit.getValue(), 1.0), rolls);
                out.merge(hit.getKey(), combined, Math::max);
            }
        }
        return out;
    }

    /**
     * Stores a table's per-item chances under {@code <kind>/<source name>}, one key per name the
     * caller maps this table to. Certain sources are skipped — absent means dependable, which keeps
     * the file (and the diff on every re-dump) small. A source reachable through several tables keeps
     * its BEST chance.
     */
    private void recordChances(JsonElement pools, String kind, String... names) {
        Map<String, Double> table = new TreeMap<>();
        for (JsonElement pool : array(pools)) {
            if (!pool.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, Double> hit : poolChances(pool.getAsJsonObject()).entrySet()) {
                // Pools are independent draws, so combine rather than take the best.
                double previous = table.getOrDefault(hit.getKey(), 0.0);
                table.put(hit.getKey(), 1.0 - (1.0 - previous) * (1.0 - hit.getValue()));
            }
        }
        for (Map.Entry<String, Double> hit : table.entrySet()) {
            if (hit.getValue() >= 1.0) {
                continue;
            }
            double rounded = Math.round(hit.getValue() * 100000.0) / 100000.0;
            for (String name : names) {
                Map<String, Double> slot =
                        chances.computeIfAbsent(hit.getKey(), k -> new TreeMap<>());
                slot.merge(kind + "/" + name, rounded, Math::max);
            }
        }
    }

    private static boolean isEnchanted(JsonObject entry) {
        for (JsonElement function : array(entry.get("functions"))) {
            if (function.isJsonObject()) {
                String name = stripNs(string(function.getAsJsonObject().get("function"), ""));
                if (ENCHANT_FUNCS.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean poolNeedsSilk(JsonObject pool) {
        for (JsonElement condition : array(pool.get("conditions"))) {
            if (!condition.isJsonObject()) {
                continue;
            }
            JsonObject cond = condition.getAsJsonObject();
            if (!stripNs(string(cond.get("condition"), "")).equals("match_tool")) {
                continue;
            }
            JsonObject predicate = obj(cond.get("predicate"));
            JsonObject predicates = obj(predicate.get("predicates"));
            JsonElement enchants = predicates.has("minecraft:enchantments")
                    ? predicates.get("minecraft:enchantments") : predicates.get("enchantments");
            for (JsonElement ench : array(enchants)) {
                if (ench.isJsonObject() && string(ench.getAsJsonObject().get("enchantments"), "").contains("silk_touch")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Structure game_id(s) a chest/archaeology/spawner loot table belongs to. */
    private static String[] structuresFor(String rel) {
        String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        if (rel.contains("trial_chamber")) {
            return new String[]{"trial_chambers"};
        }
        if (name.startsWith("bastion")) {
            return new String[]{"bastion_remnant"};
        }
        if (name.startsWith("stronghold")) {
            return new String[]{"stronghold"};
        }
        if (name.startsWith("shipwreck")) {
            return new String[]{"shipwreck"};
        }
        if (name.startsWith("ancient_city")) {
            return new String[]{"ancient_city"};
        }
        if (name.startsWith("underwater_ruin")) {
            return new String[]{"ocean_ruin_cold", "ocean_ruin_warm"};
        }
        if (rel.startsWith("village/")) {
            for (String biome : List.of("desert", "plains", "savanna", "snowy", "taiga")) {
                if (name.contains(biome)) {
                    return new String[]{"village_" + biome};
                }
            }
            return VILLAGE_BIOMES.toArray(new String[0]);
        }
        return CHEST_STRUCTURE.getOrDefault(name, new String[0]);
    }

    // -- trades / food / tags ----------------------------------------------

    private void onTrade(String path, JsonObject trade) {
        // path = "villager_trade/<profession>/<file>.json".
        String rel = path.substring("villager_trade/".length(), path.length() - ".json".length());
        int slash = rel.indexOf('/');
        if (slash < 0) {
            return;
        }
        String profession = rel.substring(0, slash);
        String fileName = rel.substring(slash + 1);
        JsonObject gives = obj(trade.get("gives"));
        String item = string(gives.get("id"), string(gives.get("item"), null));
        if (item != null && !item.isEmpty()) {
            trades.computeIfAbsent(stripNs(item), k -> new TreeSet<>()).add(profession + TRADE_SEP + fileName);
        }
    }

    private void onItemTag(String path, JsonObject tag) {
        // path = "tags/item/<rel>.json" (or "tags/items/..."); key by the rel, like the offline tool.
        String prefix = path.startsWith("tags/items/") ? "tags/items/" : "tags/item/";
        String rel = path.substring(prefix.length(), path.length() - ".json".length());
        if (tag.has("values") && tag.get("values").isJsonArray()) {
            itemTags.put(rel, tag.getAsJsonArray("values"));
        }
        String base = path.substring(path.lastIndexOf('/') + 1);
        if (base.endsWith("_food.json") || base.endsWith("_tempt_items.json")) {
            String mob = base.replace("_food.json", "").replace("_tempt_items.json", "");
            for (JsonElement value : array(tag.get("values"))) {
                String item = tagValueId(value);
                if (!item.isEmpty() && !item.startsWith("#")) {  // a nested #tag is not an item id
                    breeding.computeIfAbsent(stripNs(item), k -> new TreeSet<>()).add(mob);
                }
            }
        }
    }

    /** Concrete item ids in an item tag, following nested {@code #tag} references. */
    private List<String> resolveTag(String tagName, Set<String> seen) {
        List<String> items = new ArrayList<>();
        if (!seen.add(tagName)) {
            return items;
        }
        JsonArray values = itemTags.get(tagName);
        if (values == null) {
            return items;
        }
        for (JsonElement value : values) {
            String entry = tagValueId(value);
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith("#")) {
                items.addAll(resolveTag(stripNs(entry.substring(1)), seen));
            } else {
                items.add(stripNs(entry));
            }
        }
        return items;
    }

    private JsonObject expandIngredient(JsonObject ingredient) {
        if (ingredient.has("any_of")) {
            JsonArray expanded = new JsonArray();
            for (JsonElement sub : ingredient.getAsJsonArray("any_of")) {
                expanded.add(expandIngredient(sub.getAsJsonObject()));
            }
            JsonObject out = new JsonObject();
            out.add("any_of", expanded);
            return out;
        }
        if (ingredient.has("tag")) {
            List<String> members = resolveTag(ingredient.get("tag").getAsString(), new TreeSet<>());
            if (!members.isEmpty()) {
                JsonArray any = new JsonArray();
                for (String member : members) {
                    JsonObject one = new JsonObject();
                    one.addProperty("item", member);
                    any.add(one);
                }
                JsonObject out = new JsonObject();
                out.add("any_of", any);
                return out;
            }
        }
        return ingredient;
    }

    // -- advancement rewards (BACAP only) -----------------------------------

    /**
     * For every advancement in this pack that carries a {@code rewards.function}, read the granting
     * reward function and record each item it gives as obtainable by completing that advancement
     * ({@code advancements} source: item -> advancement game_id). BACAP advancements give items through
     * {@code bacap_rewards:<tab>/<name>}, whose actual {@code give} commands live in the parallel
     * {@code reward/<tab>/<name>} function; reading that subtree (not the dispatcher) keeps cosmetic
     * trophy/XP grants out. Attribution is by the advancement's own game_id (namespace from its
     * resource), so a reward attached to an overridden {@code minecraft:} advancement is credited to it.
     */
    private void collectAdvancementRewards(ResourceManager rm) {
        Map<Identifier, Resource> advs = rm.listResources("advancement", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> sorted = new ArrayList<>(advs.entrySet());
        sorted.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
        for (Map.Entry<Identifier, Resource> entry : sorted) {
            Identifier id = entry.getKey();
            String path = id.getPath();  // "advancement/<rel>.json"
            String rel = path.substring("advancement/".length(), path.length() - ".json".length());
            String advGameId = id.getNamespace() + ":" + rel;
            JsonObject json;
            try (Reader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                json = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } catch (Exception ignored) {
                continue;
            }
            if (json == null) {
                continue;
            }
            String rewardFn = string(obj(json.get("rewards")).get("function"), null);
            if (rewardFn == null || rewardFn.isEmpty()) {
                continue;
            }
            for (String item : rewardItems(rm, rewardFn)) {
                advancements.computeIfAbsent(item, k -> new TreeSet<>()).add(advGameId);
            }
        }
    }

    /** Items given by a {@code rewards.function}: read the parallel {@code reward/...} give-function
     *  (falling back to the named function itself), and collect each {@code give}'s minecraft item id. */
    private Set<String> rewardItems(ResourceManager rm, String rewardFn) {
        int colon = rewardFn.indexOf(':');
        String ns = colon >= 0 ? rewardFn.substring(0, colon) : "minecraft";
        String fnPath = colon >= 0 ? rewardFn.substring(colon + 1) : rewardFn;
        Set<String> items = new TreeSet<>();
        // Prefer the give-only reward/ subtree; fall back to the named function for non-BACAP layouts.
        if (!parseGives(rm, Identifier.fromNamespaceAndPath(ns, "function/reward/" + fnPath + ".mcfunction"), items)) {
            parseGives(rm, Identifier.fromNamespaceAndPath(ns, "function/" + fnPath + ".mcfunction"), items);
        }
        return items;
    }

    /** Parse {@code give} commands from a function resource into {@code items}; returns whether it
     *  existed. Each {@code give <selector> [minecraft:]<id>[components] [count]} contributes {@code id}
     *  (vanilla items only; nested container contents are not unpacked). */
    private static boolean parseGives(ResourceManager rm, Identifier funcId, Set<String> items) {
        Resource resource = rm.getResource(funcId).orElse(null);
        if (resource == null) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.open(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String item = giveItem(line);
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (Exception ignored) {
            // unreadable function — treat as no items
        }
        return true;
    }

    /** The minecraft item id a {@code give} command line grants, or {@code null} if the line isn't a
     *  give of a vanilla item. Tokens carry no spaces in BACAP reward functions (single-line, no spaces
     *  inside selectors/components), so whitespace splitting is safe. */
    private static String giveItem(String line) {
        int give = line.indexOf("give @");
        if (give < 0) {
            return null;
        }
        String after = line.substring(give + "give ".length()).trim();  // "<selector> <item> [count]"
        int afterSelector = after.indexOf(' ');
        if (afterSelector < 0) {
            return null;
        }
        String rest = after.substring(afterSelector + 1).trim();
        int end = rest.indexOf(' ');
        String token = end < 0 ? rest : rest.substring(0, end);          // "[minecraft:]<id>[components]"
        int bracket = token.indexOf('[');
        if (bracket >= 0) {
            token = token.substring(0, bracket);                        // drop item components
        }
        int colon = token.indexOf(':');
        if (colon >= 0) {
            if (!token.substring(0, colon).equals("minecraft")) {
                return null;                                            // a modded/custom item — skip
            }
            token = token.substring(colon + 1);
        }
        return token.isEmpty() ? null : token;
    }

    // -- emit ---------------------------------------------------------------

    private JsonObject table() {
        for (Map.Entry<String, String[]> hard : HARDCODED_DROPS.entrySet()) {
            TreeSet<String> set = drops.computeIfAbsent(hard.getKey(), k -> new TreeSet<>());
            for (String mob : hard.getValue()) {
                set.add(mob);
            }
        }
        TreeSet<String> items = new TreeSet<>();
        items.addAll(advancements.keySet());
        items.addAll(recipes.keySet());
        items.addAll(drops.keySet());
        items.addAll(mining.keySet());
        items.addAll(silkMining.keySet());
        items.addAll(structures.keySet());
        items.addAll(archaeology.keySet());
        items.addAll(trades.keySet());
        items.addAll(breeding.keySet());
        items.addAll(gameplay.keySet());

        JsonObject out = new JsonObject();
        for (String item : items) {
            JsonObject record = new JsonObject();  // keys inserted alphabetically (sort_keys parity)
            if (advancements.containsKey(item)) {
                record.add("advancements", stringArray(advancements.get(item)));
            }
            if (archaeology.containsKey(item)) {
                record.add("archaeology", stringArray(archaeology.get(item)));
            }
            if (breeding.containsKey(item)) {
                record.add("breeding", stringArray(breeding.get(item)));
            }
            JsonObject itemChances = chancesFor(item);
            if (itemChances.size() > 0) {
                record.add("chances", itemChances);
            }
            if (drops.containsKey(item)) {
                record.add("drops", stringArray(drops.get(item)));
            }
            if (gameplay.containsKey(item)) {
                record.add("gameplay", stringArray(gameplay.get(item)));
            }
            if (mining.containsKey(item)) {
                record.add("mining", stringArray(mining.get(item)));
            }
            if (recipes.containsKey(item)) {
                JsonArray array = new JsonArray();
                for (JsonObject recipe : recipes.get(item)) {
                    JsonObject expanded = new JsonObject();
                    JsonArray ingredients = new JsonArray();
                    for (JsonElement ing : recipe.getAsJsonArray("ingredients")) {
                        ingredients.add(expandIngredient(ing.getAsJsonObject()));
                    }
                    expanded.add("ingredients", ingredients);
                    expanded.add("station", recipe.get("station"));
                    array.add(expanded);
                }
                record.add("recipes", array);
            }
            if (silkMining.containsKey(item)) {
                record.add("silk_mining", stringArray(silkMining.get(item)));
            }
            if (structures.containsKey(item)) {
                record.add("structures", stringArray(structures.get(item)));
            }
            if (trades.containsKey(item)) {
                JsonArray array = new JsonArray();
                for (String pair : trades.get(item)) {
                    int sep = pair.indexOf(TRADE_SEP);
                    JsonArray one = new JsonArray();
                    one.add(sep >= 0 ? pair.substring(0, sep) : pair);
                    one.add(sep >= 0 ? pair.substring(sep + TRADE_SEP.length()) : "");
                    array.add(one);
                }
                record.add("trades", array);
            }
            out.add(item, record);
        }
        return out;
    }

    /**
     * An item's chance map, restricted to the sources its record actually kept. A loot table can
     * mention an item the record drops elsewhere (a silk-only block, a referenced sub-table), and a
     * dangling key would read as a source that isn't there.
     */
    private JsonObject chancesFor(String item) {
        JsonObject out = new JsonObject();
        Map<String, Double> known = chances.get(item);
        if (known == null) {
            return out;
        }
        Set<String> kept = new TreeSet<>();
        addKept(kept, "drops", drops.get(item));
        addKept(kept, "gameplay", gameplay.get(item));
        addKept(kept, "mining", mining.get(item));
        addKept(kept, "silk_mining", silkMining.get(item));
        addKept(kept, "structures", structures.get(item));
        addKept(kept, "archaeology", archaeology.get(item));
        for (Map.Entry<String, Double> hit : known.entrySet()) {
            if (kept.contains(hit.getKey())) {
                out.addProperty(hit.getKey(), hit.getValue());
            }
        }
        return out;
    }

    private static void addKept(Set<String> kept, String kind, TreeSet<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            kept.add(kind + "/" + name);
        }
    }

    // -- helpers ------------------------------------------------------------

    private static String tagValueId(JsonElement value) {
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonObject()) {
            return string(value.getAsJsonObject().get("id"), "");
        }
        return "";
    }

    private static JsonArray stringArray(TreeSet<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
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

}
