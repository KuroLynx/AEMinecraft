package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

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
 * {@code recipes / drops / mining / silk_mining / structures / trades / breeding / gameplay}.
 */
final class AcquisitionDump {
    private AcquisitionDump() {}

    private static final List<String> ENCHANT_FUNCS = List.of(
            "enchant_randomly", "enchant_with_levels", "set_enchantments");
    private static final List<String> VILLAGE_BIOMES = List.of(
            "Village (Desert)", "Village (Plains)", "Village (Savanna)",
            "Village (Snowy)", "Village (Taiga)");
    private static final Map<String, String[]> CHEST_STRUCTURE = Map.ofEntries(
            Map.entry("abandoned_mineshaft", new String[]{"Mineshaft"}),
            Map.entry("buried_treasure", new String[]{"Buried Treasure"}),
            Map.entry("desert_pyramid", new String[]{"Desert Pyramid"}),
            Map.entry("desert_well", new String[]{"Desert Well"}),
            Map.entry("end_city_treasure", new String[]{"End City"}),
            Map.entry("igloo_chest", new String[]{"Igloo"}),
            Map.entry("jungle_temple", new String[]{"Jungle Pyramid"}),
            Map.entry("jungle_temple_dispenser", new String[]{"Jungle Pyramid"}),
            Map.entry("nether_bridge", new String[]{"Nether Fortress"}),
            Map.entry("pillager_outpost", new String[]{"Pillager Outpost"}),
            Map.entry("ruined_portal", new String[]{"Ruined Portal"}),
            Map.entry("simple_dungeon", new String[]{"Dungeon"}),
            Map.entry("woodland_mansion", new String[]{"Mansion"}),
            Map.entry("ocean_ruin_cold", new String[]{"Ocean Ruin (Cold)"}),
            Map.entry("ocean_ruin_warm", new String[]{"Ocean Ruin (Warm)"}),
            Map.entry("trail_ruins_common", new String[]{"Trail Ruins"}),
            Map.entry("trail_ruins_rare", new String[]{"Trail Ruins"}));
    // Drops hard-coded in entity code (no loot table): item -> mob loot-table basename(s).
    private static final Map<String, String[]> HARDCODED_DROPS = Map.of("nether_star", new String[]{"wither"});
    private static final String TRADE_SEP = "";  // joins (profession, file); sorts below text

    // -- accumulators (insertion-ordered recipe lists; sorted member sets) ----
    private final Map<String, List<JsonObject>> recipes = new TreeMap<>();
    private final Map<String, TreeSet<String>> drops = new TreeMap<>();
    private final Map<String, TreeSet<String>> mining = new TreeMap<>();
    private final Map<String, TreeSet<String>> silkMining = new TreeMap<>();
    private final Map<String, TreeSet<String>> structures = new TreeMap<>();
    private final Map<String, TreeSet<String>> trades = new TreeMap<>();  // "proffile"
    private final Map<String, TreeSet<String>> breeding = new TreeMap<>();
    private final Map<String, TreeSet<String>> gameplay = new TreeMap<>();
    private final Map<String, JsonArray> itemTags = new TreeMap<>();  // bare path -> raw values

    static JsonObject build(MinecraftServer server) {
        AcquisitionDump dump = new AcquisitionDump();
        ResourceManager rm = server.getResourceManager();
        // Tags first so recipe ingredient expansion can resolve them, then recipes / loot / trades.
        dump.forEachJson(rm, "tags/item", dump::onItemTag);
        dump.forEachJson(rm, "tags/items", dump::onItemTag);
        dump.forEachJson(rm, "recipe", (path, json) -> dump.onRecipe(json));
        dump.forEachJson(rm, "recipes", (path, json) -> dump.onRecipe(json));
        dump.forEachJson(rm, "loot_table", dump::onLoot);
        dump.forEachJson(rm, "loot_tables", dump::onLoot);
        dump.forEachJson(rm, "villager_trade", dump::onTrade);
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
                case "chests", "archaeology", "dispensers", "spawners" -> {
                    for (String struct : structuresFor(rel)) {
                        structures.computeIfAbsent(item, k -> new TreeSet<>()).add(struct);
                    }
                }
                case "shearing" -> { /* wool/etc from shearing a mob — covered by the mob */ }
                default -> gameplay.computeIfAbsent(item, k -> new TreeSet<>()).add(fileName);
            }
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

    /** Canonical structure name(s) a chest/archaeology/spawner loot table belongs to. */
    private static String[] structuresFor(String rel) {
        String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        if (rel.contains("trial_chamber")) {
            return new String[]{"Trial Chambers"};
        }
        if (name.startsWith("bastion")) {
            return new String[]{"Bastion Remnant"};
        }
        if (name.startsWith("stronghold")) {
            return new String[]{"Stronghold"};
        }
        if (name.startsWith("shipwreck")) {
            return new String[]{"Shipwreck"};
        }
        if (name.startsWith("ancient_city")) {
            return new String[]{"Ancient City"};
        }
        if (name.startsWith("underwater_ruin")) {
            return new String[]{"Ocean Ruin (Cold)", "Ocean Ruin (Warm)"};
        }
        if (rel.startsWith("village/")) {
            for (String biome : List.of("desert", "plains", "savanna", "snowy", "taiga")) {
                if (name.contains(biome)) {
                    return new String[]{"Village (" + capitalize(biome) + ")"};
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

    // -- emit ---------------------------------------------------------------

    private JsonObject table() {
        for (Map.Entry<String, String[]> hard : HARDCODED_DROPS.entrySet()) {
            TreeSet<String> set = drops.computeIfAbsent(hard.getKey(), k -> new TreeSet<>());
            for (String mob : hard.getValue()) {
                set.add(mob);
            }
        }
        TreeSet<String> items = new TreeSet<>();
        items.addAll(recipes.keySet());
        items.addAll(drops.keySet());
        items.addAll(mining.keySet());
        items.addAll(silkMining.keySet());
        items.addAll(structures.keySet());
        items.addAll(trades.keySet());
        items.addAll(breeding.keySet());
        items.addAll(gameplay.keySet());

        JsonObject out = new JsonObject();
        for (String item : items) {
            JsonObject record = new JsonObject();  // keys inserted alphabetically (sort_keys parity)
            if (breeding.containsKey(item)) {
                record.add("breeding", stringArray(breeding.get(item)));
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

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
