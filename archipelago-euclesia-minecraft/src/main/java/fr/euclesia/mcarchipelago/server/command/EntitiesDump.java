package fr.euclesia.mcarchipelago.server.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;

import java.io.BufferedReader;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds {@code entities.json} — the data-driven MOB registry, the live counterpart of the (now
 * retired) hand-written {@code mobs.csv}. Every field is read from the RUNNING game so vanilla + mods
 * dump uniformly, the same principle as {@link PackDump}:
 * <ul>
 *   <li>{@code category} (passive/neutral/hostile/boss), {@code tameable}, {@code leashable},
 *       {@code breedable} — runtime CLASS BEHAVIOUR, read off a throwaway entity instance. This is
 *       why entities can only be dumped from {@code /aem dump entities} in a loaded world (it needs a
 *       {@link ServerLevel}); unlike {@link PackDump} it can't run from the title-menu UI.</li>
 *   <li>{@code region} (Overworld/Nether/The End) — derived from biome spawn data: which biomes list
 *       the mob in their {@code spawners}, mapped to a dimension via the {@code is_nether}/{@code is_end}
 *       biome tags. A mob's region is the SHALLOWEST dimension it naturally spawns in (where you first
 *       meet it); structure-spawned mobs with no biome spawner fall back to {@link #FALLBACK_REGION}.</li>
 * </ul>
 * The unlock's AP classification is intentionally NOT here — the apworld derives it per-seed from the
 * goal (see {@code MCWorld._mob_classification}), exactly like structure-unlock classifications.
 */
public final class EntitiesDump {
    private EntitiesDump() {}

    /** Bosses gate the goal, not a spawn category — a fixed known set (no generic registry flag). */
    private static final Set<String> BOSSES =
            Set.of("minecraft:ender_dragon", "minecraft:wither", "minecraft:elder_guardian", "minecraft:warden");

    /** Region for mobs with no biome spawner (built / structure-only / End-ship): they can't be
     *  derived from biome spawn data, so a small game-knowledge fallback supplies it; anything not
     *  listed and not naturally spawning defaults to Overworld (golems, villagers, warden, …). */
    private static final Map<String, String> FALLBACK_REGION = Map.of(
            "minecraft:ender_dragon", "The End",
            "minecraft:shulker", "The End",
            "minecraft:blaze", "Nether",
            "minecraft:wither_skeleton", "Nether",
            "minecraft:piglin_brute", "Nether");

    /**
     * Build the entities array (sorted by game_id so the list index = stable Entity-Unlock id).
     */
    public static JsonArray build(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ResourceManager rm = server.getResourceManager();
        Map<String, String> regionByMob = regions(rm);

        // game_id -> record, TreeMap keeps deterministic game_id order.
        Map<String, JsonObject> records = new TreeMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue;  // items, projectiles, boats, armor stands, …
            }
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String gameId = key.toString();

            Entity sample = type.create(level, EntitySpawnReason.COMMAND);
            String category;
            boolean tameable = false;
            boolean leashable = false;
            boolean breedable = false;
            if (sample instanceof Mob mob) {
                category = categoryOf(gameId, type, mob);
                tameable = mob instanceof TamableAnimal;
                leashable = mob.canBeLeashed();
                breedable = mob instanceof Animal;  // the breedable farm-animal base (coarse "breed any" flag)
                mob.discard();
            } else {
                // create() returned null or a non-Mob LivingEntity; keep it only if it's clearly a mob
                // category, with behaviour flags defaulted off (can't introspect without an instance).
                if (sample != null) {
                    sample.discard();
                }
                category = BOSSES.contains(gameId) ? "boss"
                        : type.getCategory() == MobCategory.MONSTER ? "hostile" : "passive";
            }

            JsonObject record = new JsonObject();
            record.addProperty("game_id", gameId);
            record.addProperty("category", category);
            record.addProperty("region", regionByMob.getOrDefault(gameId, FALLBACK_REGION.getOrDefault(gameId, "Overworld")));
            record.addProperty("breedable", breedable);
            record.addProperty("tameable", tameable);
            record.addProperty("leashable", leashable);
            records.put(gameId, record);
        }

        JsonArray out = new JsonArray();
        records.values().forEach(out::add);
        return out;
    }

    /** boss (known set) > neutral (NeutralMob) > hostile (MONSTER category) > passive. */
    private static String categoryOf(String gameId, EntityType<?> type, Mob mob) {
        if (BOSSES.contains(gameId)) {
            return "boss";
        }
        if (mob instanceof NeutralMob) {
            return "neutral";
        }
        return type.getCategory() == MobCategory.MONSTER ? "hostile" : "passive";
    }

    // -- region from biome spawn data ---------------------------------------

    /**
     * {@code game_id -> region}: read every {@code worldgen/biome/*.json}'s {@code spawners}, recording
     * which biomes each entity spawns in; a mob's region is the shallowest spawning dimension
     * (Overworld < Nether < The End), classified via the {@code is_nether}/{@code is_end} biome tags.
     */
    private static Map<String, String> regions(ResourceManager rm) {
        Set<String> nether = biomesInTag(rm, "is_nether");
        Set<String> end = biomesInTag(rm, "is_end");

        // entity game_id -> {overworld?, nether?, end?}
        Map<String, boolean[]> hit = new java.util.HashMap<>();
        Map<Identifier, Resource> biomes =
                rm.listResources("worldgen/biome", id -> id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> entry : biomes.entrySet()) {
            String biome = bareBiomeName(entry.getKey());
            String dim = end.contains(biome) ? "end" : nether.contains(biome) ? "nether" : "overworld";
            JsonObject json = readJson(entry.getValue());
            if (json == null || !json.has("spawners") || !json.get("spawners").isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> cat : json.getAsJsonObject("spawners").entrySet()) {
                if (!cat.getValue().isJsonArray()) {
                    continue;
                }
                for (JsonElement e : cat.getValue().getAsJsonArray()) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonElement typeEl = e.getAsJsonObject().get("type");
                    if (typeEl == null || !typeEl.isJsonPrimitive()) {
                        continue;
                    }
                    boolean[] flags = hit.computeIfAbsent(normalizeId(typeEl.getAsString()), k -> new boolean[3]);
                    if (dim.equals("overworld")) flags[0] = true;
                    else if (dim.equals("nether")) flags[1] = true;
                    else flags[2] = true;
                }
            }
        }

        Map<String, String> out = new java.util.HashMap<>();
        for (Map.Entry<String, boolean[]> e : hit.entrySet()) {
            boolean[] f = e.getValue();
            out.put(e.getKey(), f[0] ? "Overworld" : f[1] ? "Nether" : f[2] ? "The End" : "Overworld");
        }
        return out;
    }

    /** Resolve a biome tag (recursively through nested #tags) to its bare biome names. */
    private static Set<String> biomesInTag(ResourceManager rm, String tag) {
        Set<String> out = new TreeSet<>();
        resolveBiomeTag(rm, tag, out, new TreeSet<>());
        return out;
    }

    private static void resolveBiomeTag(ResourceManager rm, String tag, Set<String> out, Set<String> seen) {
        if (!seen.add(tag)) {
            return;
        }
        // tags can be contributed by several packs (vanilla + datapacks) under the same id.
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "tags/worldgen/biome/" + tag + ".json");
        for (Resource resource : rm.getResourceStack(id)) {
            JsonObject json = readJson(resource);
            if (json == null || !json.has("values") || !json.get("values").isJsonArray()) {
                continue;
            }
            for (JsonElement value : json.getAsJsonArray("values")) {
                if (!value.isJsonPrimitive()) {
                    continue;
                }
                String v = value.getAsString();
                if (v.startsWith("#")) {
                    resolveBiomeTag(rm, bareName(v.substring(1)), out, seen);
                } else {
                    out.add(bareName(v));
                }
            }
        }
    }

    // -- small helpers ------------------------------------------------------

    private static JsonObject readJson(Resource resource) {
        try (BufferedReader reader = resource.openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /** "minecraft:worldgen/biome/plains.json" -> "plains". */
    private static String bareBiomeName(Identifier biomeResource) {
        String path = biomeResource.getPath();
        path = path.substring("worldgen/biome/".length(), path.length() - ".json".length());
        return path;
    }

    /** strip a leading namespace ("minecraft:nether_wastes" -> "nether_wastes"). */
    private static String bareName(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** normalise an entity id to a full "minecraft:foo" game_id (bare ids get the implicit namespace). */
    private static String normalizeId(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }
}
