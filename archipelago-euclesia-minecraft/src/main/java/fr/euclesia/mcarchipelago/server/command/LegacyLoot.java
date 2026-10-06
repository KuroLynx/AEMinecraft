package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rewrites a loot table into the pre-26.3 shape the dump's readers were written against, so they
 * read every version the same way. A table already in that shape comes back unchanged.
 *
 * <p>26.3's changes, undone here: a pool/entry/function holds one {@code condition} (an object, or
 * the id of a {@code predicate/} file) instead of a {@code conditions} list, and several are joined
 * with {@code all_of}; {@code functions} became {@code modifier}; a condition's kind moved from
 * {@code condition} to {@code type} and a function's from {@code function} to {@code type};
 * {@code block_state_property} renamed {@code block}/{@code properties} to {@code blocks}/{@code state};
 * a tag entry moved its tag from {@code name} to {@code items}.
 */
final class LegacyLoot {
    private LegacyLoot() {}

    static JsonObject convert(JsonObject table, ResourceManager rm) {
        JsonObject out = table.deepCopy();
        holder(out, rm);
        return out;
    }

    /**
     * An advancement with its criteria in the pre-26.3 shape. A criterion's entity fields
     * ({@code player}, {@code entity}, {@code child}…) hold loot conditions, which 26.3 writes as one
     * {@code {"type": ...}} object (or a predicate-file id) where older versions wrote a list of
     * {@code {"condition": ...}}. An entity field holding a plain entity predicate is left alone: its
     * {@code type} is an entity type, not a registered loot condition.
     */
    static JsonObject convertAdvancement(JsonObject advancement, ResourceManager rm) {
        JsonElement criteria = advancement.get("criteria");
        if (criteria == null || !criteria.isJsonObject()) {
            return advancement;
        }
        JsonObject out = advancement.deepCopy();
        for (Map.Entry<String, JsonElement> criterion : out.getAsJsonObject("criteria").entrySet()) {
            JsonElement conditions = criterion.getValue().isJsonObject()
                    ? criterion.getValue().getAsJsonObject().get("conditions") : null;
            if (conditions == null || !conditions.isJsonObject()) {
                continue;
            }
            JsonObject fields = conditions.getAsJsonObject();
            for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
                JsonElement list = lootList(field.getValue(), rm);
                if (list != null) {
                    field.setValue(list);
                } else if (field.getKey().equals("victims") && field.getValue().isJsonArray()) {
                    // one list of conditions per victim before 26.3, one condition per victim since
                    JsonArray victims = new JsonArray();
                    for (JsonElement victim : field.getValue().getAsJsonArray()) {
                        JsonElement converted = lootList(victim, rm);
                        victims.add(converted != null ? converted : victim);
                    }
                    field.setValue(victims);
                }
            }
            // 26.3 pluralised single-valued fields
            singular(fields, "recipes", "recipe_id", true);
            singular(fields, "loot_tables", "loot_table", true);
            // enter_block, slide_down_block, bee_nest_destroyed: only a bare id, since a "blocks"
            // LIST is older than 26.3 (BACAP 1.21 writes one) and already read as it is
            singular(fields, "blocks", "block", false);
            unhashTagIds(fields);
            unwrapPotions(fields);
            legacyEntityPredicates(fields);
        }
        return out;
    }

    /** Entity sub-predicates 26.2 moved under a {@code minecraft:} key (26.1.2 wrote them bare). */
    private static final Set<String> ENTITY_SUBPREDICATES = Set.of("location", "flags", "components",
            "equipment", "distance", "vehicle", "passenger", "stepping_on", "targeted_entity", "effects",
            "nbt", "movement", "movement_affected_by", "periodic_tick", "slots", "team");

    /**
     * Entity predicates back into the 26.1.2 shape. 26.2 renamed an entity predicate's {@code type} to
     * {@code minecraft:entity_type} ({@code entity_type} in BACAP), prefixed its sub-predicates
     * ({@code minecraft:location}, {@code minecraft:flags}…), and split {@code type_specific} by kind
     * ({@code "minecraft:type_specific/player": {...}} for {@code type_specific: {type: player, ...}}).
     * The compiler reads the entity type from {@code type}, so on 26.2+ every "this mob" gate (breed,
     * kill, tame a specific animal) compiled to nothing.
     */
    private static void legacyEntityPredicates(JsonElement element) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(LegacyLoot::legacyEntityPredicates);
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject node = element.getAsJsonObject();
        List<Map.Entry<String, JsonElement>> entries = List.copyOf(node.entrySet());
        entries.forEach(entry -> legacyEntityPredicates(entry.getValue()));
        boolean reshaped = entries.stream().anyMatch(entry -> legacyKey(entry.getKey(), node) != null
                || entry.getKey().startsWith("minecraft:type_specific/"));
        if (!reshaped) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : entries) {
            node.remove(entry.getKey());
        }
        for (Map.Entry<String, JsonElement> entry : entries) {
            String key = entry.getKey();
            if (key.startsWith("minecraft:type_specific/")) {
                JsonObject typeSpecific = new JsonObject();
                typeSpecific.addProperty("type", "minecraft:" + key.substring("minecraft:type_specific/".length()));
                if (entry.getValue().isJsonObject()) {
                    entry.getValue().getAsJsonObject().entrySet().forEach(e -> typeSpecific.add(e.getKey(), e.getValue()));
                }
                node.add("type_specific", typeSpecific);
            } else {
                String legacy = legacyKey(key, node);
                node.add(legacy != null ? legacy : key, entry.getValue());
            }
        }
    }

    /** The 26.1.2 name for an entity predicate key, or null if it has none (or never changed). */
    private static String legacyKey(String key, JsonObject node) {
        if ((key.equals("minecraft:entity_type") || key.equals("entity_type")) && !node.has("type")) {
            return "type";
        }
        if (key.startsWith("minecraft:") && ENTITY_SUBPREDICATES.contains(key.substring("minecraft:".length()))) {
            return key.substring("minecraft:".length());
        }
        return null;
    }

    /** 26.3 wraps a potion test's id(s) as {@code {"potions": ...}} under {@code potion_contents} (an
     *  item sub-predicate) and {@code potion} (brewed_potion); older versions held the id(s) directly. */
    private static void unwrapPotions(JsonElement element) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(LegacyLoot::unwrapPotions);
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                String key = entry.getKey().replace("minecraft:", "");
                if ((key.equals("potion_contents") || key.equals("potion")) && value.isJsonObject()
                        && value.getAsJsonObject().size() == 1 && value.getAsJsonObject().has("potions")) {
                    entry.setValue(value.getAsJsonObject().get("potions"));
                } else {
                    unwrapPotions(value);
                }
            }
        }
    }

    /** A loot condition (object, or the id of an existing predicate file) as the old list; else null. */
    private static JsonArray lootList(JsonElement value, ResourceManager rm) {
        // A string is a predicate-file id only when that file exists: most string fields are
        // something else entirely (a recipe id, an item id).
        JsonElement loot = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? predicateFile(value.getAsString(), rm).map(JsonElement.class::cast).orElse(null)
                : value;
        if (loot == null || !isLootCondition(loot)) {
            return null;
        }
        JsonArray list = new JsonArray();
        flattenAllOf(condition(loot, rm), list);
        return list;
    }

    /** {@code plural} holding one id (bare, or a one-element list if {@code unwrapList}) back to the old
     *  {@code single}. */
    private static void singular(JsonObject fields, String plural, String single, boolean unwrapList) {
        JsonElement value = fields.get(plural);
        if (value == null || fields.has(single)) {
            return;
        }
        if (unwrapList && value.isJsonArray() && value.getAsJsonArray().size() == 1) {
            value = value.getAsJsonArray().get(0);
        }
        if (value.isJsonPrimitive()) {
            fields.remove(plural);
            fields.add(single, value);
        }
    }

    /** 26.3 writes a damage-type tag test's id with its '#': {@code {"id": "#minecraft:x", "expected": true}}. */
    private static void unhashTagIds(JsonElement element) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(LegacyLoot::unhashTagIds);
        } else if (element.isJsonObject()) {
            JsonObject node = element.getAsJsonObject();
            JsonElement id = node.get("id");
            if (node.has("expected") && id != null && id.isJsonPrimitive() && id.getAsString().startsWith("#")) {
                node.addProperty("id", id.getAsString().substring(1));
            }
            node.entrySet().forEach(entry -> unhashTagIds(entry.getValue()));
        }
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static boolean isLootCondition(JsonElement element) {
        if (!element.isJsonObject() || !element.getAsJsonObject().has("type")
                || !element.getAsJsonObject().get("type").isJsonPrimitive()) {
            return false;
        }
        Identifier type = Identifier.tryParse(element.getAsJsonObject().get("type").getAsString());
        return type != null && BuiltInRegistries.LOOT_CONDITION_TYPE.containsKey(type);
    }

    /** A table, pool, entry or function: anything that can carry conditions and functions. */
    private static void holder(JsonObject node, ResourceManager rm) {
        // a tag entry names its tag in "items" as "#ns:tag" from 26.3, in "name" as "ns:tag" before
        if (node.has("items") && !node.has("name") && node.get("items").isJsonPrimitive()
                && node.has("type") && node.get("type").getAsString().endsWith("tag")) {
            String tag = node.remove("items").getAsString();
            node.addProperty("name", tag.startsWith("#") ? tag.substring(1) : tag);
        }
        JsonElement condition = node.remove("condition");
        if (condition != null) {
            JsonArray conditions = new JsonArray();
            flattenAllOf(condition(condition, rm), conditions);
            node.add("conditions", conditions);
        }
        JsonElement modifier = node.remove("modifier");
        if (modifier != null) {
            JsonArray functions = new JsonArray();
            for (JsonElement function : modifier.isJsonArray() ? modifier.getAsJsonArray() : List.of(modifier)) {
                if (function.isJsonObject()) {
                    JsonObject fn = function.getAsJsonObject();
                    renameKind(fn, "function");
                    holder(fn, rm);
                }
                functions.add(function);
            }
            node.add("functions", functions);
        }
        for (String child : List.of("pools", "entries", "children", "functions")) {
            JsonElement list = node.get(child);
            if (list != null && list.isJsonArray() && !(child.equals("functions") && modifier != null)) {
                for (JsonElement item : list.getAsJsonArray()) {
                    if (item.isJsonObject()) {
                        holder(item.getAsJsonObject(), rm);
                    }
                }
            }
        }
    }

    /** An all_of at the top of a holder's condition is the old list: its terms each become one entry. */
    private static void flattenAllOf(JsonObject condition, JsonArray out) {
        if (condition.get("condition") != null && condition.get("condition").getAsString().endsWith("all_of")
                && condition.has("terms")) {
            for (JsonElement term : condition.getAsJsonArray("terms")) {
                out.add(term);
            }
        } else {
            out.add(condition);
        }
    }

    /** One condition in the old shape, resolving a predicate-file id and recursing into composites. */
    private static JsonObject condition(JsonElement element, ResourceManager rm) {
        JsonObject cond = element.isJsonPrimitive()
                ? predicateFile(element.getAsString(), rm).orElseGet(JsonObject::new)
                : element.getAsJsonObject().deepCopy();
        renameKind(cond, "condition");
        JsonElement terms = cond.get("terms");
        if (terms != null && terms.isJsonArray()) {
            JsonArray converted = new JsonArray();
            for (JsonElement term : terms.getAsJsonArray()) {
                converted.add(condition(term, rm));
            }
            cond.add("terms", converted);
        }
        JsonElement term = cond.get("term");
        if (term != null) {
            cond.add("term", condition(term, rm));
        }
        // 26.3's match_block with one block is the old block_state_property (a tag or list has no
        // old equivalent and stays as it is)
        if (string(cond.get("condition")).endsWith("match_block") && cond.has("blocks")
                && cond.get("blocks").isJsonPrimitive() && !cond.get("blocks").getAsString().startsWith("#")) {
            cond.addProperty("condition", "minecraft:block_state_property");
        }
        if (string(cond.get("condition")).endsWith("block_state_property")) {
            rename(cond, "blocks", "block");
            rename(cond, "state", "properties");
        }
        return cond;
    }

    /** 26.3 names a condition's (or function's) kind {@code type}; the old shape used {@code key}. */
    private static void renameKind(JsonObject node, String key) {
        if (!node.has(key) && node.has("type") && node.get("type").isJsonPrimitive()) {
            node.add(key, node.remove("type"));
        }
    }

    private static void rename(JsonObject node, String from, String to) {
        if (!node.has(to) && node.has(from)) {
            node.add(to, node.remove(from));
        }
    }

    /** {@code data/<ns>/predicate/<path>.json} for the id {@code <ns>:<path>}. */
    private static Optional<JsonObject> predicateFile(String id, ResourceManager rm) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) {
            return Optional.empty();
        }
        Identifier file = Identifier.fromNamespaceAndPath(parsed.getNamespace(), "predicate/" + parsed.getPath() + ".json");
        Optional<Resource> resource = rm.getResource(file);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonElement parsedJson = JsonParser.parseReader(reader);
            return parsedJson.isJsonObject() ? Optional.of(parsedJson.getAsJsonObject()) : Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
    }
}
