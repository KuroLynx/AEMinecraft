package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * How likely each block is to be in one generated jigsaw structure, read off its template pools.
 *
 * <p>The start pool draws one element; each jigsaw in a template draws one element of its own pool,
 * down to the structure's {@code size}. A block is there when the drawn template has it or any of its
 * jigsaws pulls it in. Draws are taken as independent and always placed (a jigsaw piece that doesn't
 * fit is dropped in game), so the chance is an upper bound. That's the lenient side: a camp's copper
 * golem statue reads 12%, not the 100% the flat palette implied, and a village bell stays at 100%.
 * A structure's {@code pool_aliases} (Trial Chambers' spawner contents) draw as the weighted mix of
 * their targets.
 */
final class JigsawChances {
    private record Template(Set<String> blocks, List<String> pools) {}

    private final ResourceManager rm;
    private final Map<String, Template> templates = new HashMap<>();
    private final Map<String, Map<String, Double>> drawn = new HashMap<>();
    // The structure being read's pool aliases: alias -> (weight, target pool).
    private final Map<String, List<Map.Entry<Double, String>>> aliases = new HashMap<>();

    JigsawChances(ResourceManager rm) {
        this.rm = rm;
    }

    /** Block (bare id) -> chance it is in one structure; {@code null} for a non-jigsaw structure. */
    Map<String, Double> of(JsonObject def) {
        if (def == null || !"minecraft:jigsaw".equals(namespaced(string(def.get("type"))))) {
            return null;
        }
        String start = string(def.get("start_pool"));
        JsonElement size = def.get("size");
        if (start.isEmpty() || size == null || !size.isJsonPrimitive()) {
            return null;
        }
        aliases.clear();
        for (JsonElement binding : array(def.get("pool_aliases"))) {
            JsonObject alias = binding.getAsJsonObject();
            if (namespaced(string(alias.get("type"))).equals("minecraft:random_group")) {
                for (JsonElement group : array(alias.get("groups"))) {
                    double weight = weight(group.getAsJsonObject());
                    for (JsonElement member : array(group.getAsJsonObject().get("data"))) {
                        bind(member.getAsJsonObject(), weight);
                    }
                }
            } else {
                bind(alias, 1);
            }
        }
        if (!aliases.isEmpty()) {
            drawn.clear();   // what a pool draws now depends on this structure's aliases
        }
        Map<String, Double> chances = draw(namespaced(start), size.getAsInt());
        if (!aliases.isEmpty()) {
            drawn.clear();
            aliases.clear();
        }
        return chances;
    }

    /** A {@code direct} (one target) or {@code random} (weighted targets) alias, at {@code weight}. */
    private void bind(JsonObject alias, double weight) {
        List<Map.Entry<Double, String>> targets = aliases.computeIfAbsent(namespaced(string(alias.get("alias"))),
                key -> new ArrayList<>());
        if (alias.has("target")) {
            targets.add(Map.entry(weight, namespaced(string(alias.get("target")))));
        }
        for (JsonElement target : array(alias.get("targets"))) {
            JsonObject entry = target.getAsJsonObject();
            targets.add(Map.entry(weight * weight(entry), namespaced(string(entry.get("data")))));
        }
    }

    private static double weight(JsonObject weighted) {
        return weighted.has("weight") ? weighted.get("weight").getAsDouble() : 1;
    }

    /** One draw from {@code pool} whose pieces may still hang {@code depth} levels below them. */
    private Map<String, Double> draw(String pool, int depth) {
        String key = pool + "#" + depth;
        Map<String, Double> known = drawn.get(key);
        if (known != null) {
            return known;
        }
        Map<String, Double> chances = new HashMap<>();
        drawn.put(key, chances);   // a pool reaching itself at the same depth can't happen: depth shrinks
        double total = 0;
        List<Map.Entry<Double, Map<String, Double>>> elements = new ArrayList<>();
        List<Map.Entry<Double, String>> targets = aliases.get(pool);
        if (targets != null) {
            for (Map.Entry<Double, String> target : targets) {
                total += target.getKey();
                elements.add(Map.entry(target.getKey(), draw(target.getValue(), depth)));
            }
        } else {
            for (JsonElement entry : array(poolJson(pool).get("elements"))) {
                JsonObject weighted = entry.getAsJsonObject();
                double weight = weight(weighted);
                total += weight;
                elements.add(Map.entry(weight, element(weighted.getAsJsonObject("element"), depth)));
            }
        }
        for (Map.Entry<Double, Map<String, Double>> element : elements) {
            element.getValue().forEach((block, chance) ->
                    chances.merge(block, element.getKey() * chance, Double::sum));
        }
        if (total > 0) {
            final double sum = total;
            chances.replaceAll((block, chance) -> chance / sum);
        }
        return chances;
    }

    private Map<String, Double> element(JsonObject element, int depth) {
        Map<String, Double> chances = new HashMap<>();
        String type = namespaced(string(element.get("element_type")));
        if (type.equals("minecraft:list_pool_element")) {
            for (JsonElement part : array(element.get("elements"))) {
                element(part.getAsJsonObject(), depth).forEach((block, chance) -> either(chances, block, chance));
            }
            return chances;
        }
        String location = string(element.get("location"));
        if (location.isEmpty()) {
            return chances;   // feature / empty elements place no template
        }
        Template template = template(namespaced(location));
        template.blocks().forEach(block -> chances.put(block, 1.0));
        if (depth > 0) {
            for (String pool : template.pools()) {
                draw(pool, depth - 1).forEach((block, chance) -> either(chances, block, chance));
            }
        }
        return chances;
    }

    /** P(a or b) for independent events. */
    private static void either(Map<String, Double> chances, String block, double chance) {
        chances.merge(block, chance, (a, b) -> 1 - (1 - a) * (1 - b));
    }

    private Template template(String location) {
        return templates.computeIfAbsent(location, id -> {
            Identifier file = Identifier.parse(id).withPath(path -> "structure/" + path + ".nbt");
            Optional<Resource> resource = rm.getResource(file);
            if (resource.isEmpty()) {
                return new Template(Set.of(), List.of());
            }
            List<String> pools = new ArrayList<>();
            try (InputStream stream = resource.get().open()) {
                CompoundTag root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
                ListTag blocks = root.getList("blocks").orElse(new ListTag());
                for (int i = 0; i < blocks.size(); i++) {
                    blocks.getCompound(i).flatMap(block -> block.getCompound("nbt")).ifPresent(nbt -> {
                        String pool = nbt.getStringOr("pool", "");
                        if (!pool.isEmpty() && !namespaced(pool).equals("minecraft:empty")) {
                            pools.add(namespaced(pool));
                        }
                    });
                }
            } catch (Exception exception) {
                // unreadable jigsaws: the template still counts for its own blocks
            }
            return new Template(PackDump.paletteBlocks(resource.get()), pools);
        });
    }

    private JsonObject poolJson(String pool) {
        Identifier file = Identifier.parse(pool).withPath(path -> "worldgen/template_pool/" + path + ".json");
        Optional<Resource> resource = rm.getResource(file);
        if (resource.isPresent()) {
            try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (Exception ignored) {
                // a pool that won't parse draws nothing
            }
        }
        return new JsonObject();
    }

    private static Iterable<JsonElement> array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : List.of();
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String namespaced(String id) {
        return id.isEmpty() || id.contains(":") ? id : "minecraft:" + id;
    }
}
