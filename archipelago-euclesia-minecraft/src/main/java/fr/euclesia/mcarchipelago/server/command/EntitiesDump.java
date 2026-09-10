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
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

import java.io.BufferedReader;
import java.lang.reflect.Field;
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
 *       {@code breedable} — runtime CLASS BEHAVIOUR, read off a throwaway entity instance. This needs
 *       a live {@link ServerLevel}, so unlike {@link PackDump} (raw datapack JSON) it can't run off a
 *       world-free resource manager: {@code /aem dump entities} reads the loaded world, and the
 *       title-menu UI spins up a disposable one ({@code HeadlessEntitiesDump}).</li>
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

    /** Registered Mobs that are never encountered in normal play — no natural spawn, no build/convert
     *  path — so the registry omits them (matching the old curated list). The registry can't tell these
     *  apart from a real mob (both are {@link Mob}s), so a tiny game-knowledge set excludes them. */
    private static final Set<String> EXCLUDED =
            Set.of("minecraft:giant", "minecraft:illusioner");

    /** {@link AbstractHorse}s the player can't tame even though the class supports it. Camel + its husk
     *  variant are derived out at runtime instead (they report {@code isTamed() == true} on a fresh
     *  sample — born ride-ready, never tamed), so the only one that needs listing is the skeleton horse:
     *  it blocks taming in {@code mobInteract} (trap-spawned ones come pre-tamed), which isn't detectable
     *  headless without a Player. Everything else extending AbstractHorse is mount-tamed
     *  (horse/donkey/mule/llama/trader_llama/zombie_horse). */
    private static final Set<String> NON_TAMEABLE_EQUINES = Set.of("minecraft:skeleton_horse");

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
        SpawnData spawns = spawnData(rm);
        Map<String, String> regionByMob = spawns.regions();

        // game_id -> record, TreeMap keeps deterministic game_id order.
        Map<String, JsonObject> records = new TreeMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String gameId = key.toString();
            if (EXCLUDED.contains(gameId)) {
                continue;  // registered but never encountered (Giant, Illusioner)
            }

            // Keep only true mobs. Filtering on Mob (not MobCategory.MISC) is deliberate: MISC holds
            // both non-mobs (items, projectiles, boats, armor stands — NOT Mobs, so skipped here) AND
            // real built/structure mobs (villagers, golems — Mobs, so KEPT). Bosses are Mobs too.
            Entity sample = type.create(level, EntitySpawnReason.COMMAND);
            if (!(sample instanceof Mob mob)) {
                if (sample != null) {
                    sample.discard();
                }
                continue;
            }
            String category = categoryOf(gameId, type, mob);
            // TamableAnimal covers wolf/cat/parrot/(zombie_)nautilus. Equines are mount-tamed (no
            // TamableAnimal interface); a FRESH sample that already reports isTamed() is one that's
            // never player-tamed (camel + its husk), and skeleton_horse is the lone non-derivable
            // holdout. NB: read isTamed() here, before isBreedable() force-tames this same sample.
            boolean tameable = mob instanceof TamableAnimal
                    || (mob instanceof AbstractHorse h && !h.isTamed() && !NON_TAMEABLE_EQUINES.contains(gameId));
            boolean leashable = mob.canBeLeashed();
            boolean breedable = isBreedable(type, level, mob);
            mob.discard();

            JsonObject record = new JsonObject();
            record.addProperty("game_id", gameId);
            record.addProperty("category", category);
            record.addProperty("region", regionByMob.getOrDefault(gameId, FALLBACK_REGION.getOrDefault(gameId, "Overworld")));
            record.add("biomes", stringArray(spawns.biomes().get(gameId)));
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

    /**
     * Whether a mob can actually be bred — the membership test for the generic "breed any animal" gate.
     * Rather than read a coarse class flag, this drives the game's own breeding code on a throwaway
     * pair: put both into the maximal <em>legitimate</em> breeding state, then ask {@link Animal#canMate}.
     * The two gates that matter, traced through {@code Animal.spawnChildFromBreeding}:
     * <ol>
     *   <li><b>{@link Animal#canFallInLove} + {@link #hasBreedingFood}</b> — can the species ever be fed
     *       into love at all? Both read on the FRESH sample (inLove == 0): {@code canFallInLove -> false}
     *       excludes mobs that never enter love ({@code HappyGhast}); {@code isFood -> false} excludes
     *       mobs with no breeding food ({@code Parrot}/{@code PolarBear}). These stateless gates drop the
     *       old {@code instanceof Animal} flag's worst "breed any" leaks without touching mutable state.</li>
     *   <li><b>{@link #hasBreedingAi}</b> — does the mob's AI actually carry a breeding behaviour? This is
     *       the gate the forced-love {@code canMate} check below is blind to. {@code zombie_nautilus} shares
     *       {@code AbstractNautilus}'s {@code isFood}/{@code canMate}/{@code getBreedOffspring} with the
     *       breedable {@code Nautilus}, so it passes every other gate — but {@code ZombieNautilusAi} only
     *       follows temptation and never makes love, so it isn't actually breedable. See {@link #hasBreedingAi}.</li>
     *   <li><b>{@link Animal#canMate}</b> in the maximal state — this is where sterility and breeding
     *       rules live: {@code Mule}/{@code AbstractHorse.canMate} is a hard {@code false} (mule excluded —
     *       it's the horse×donkey offspring, not an active breeder), horses/llamas/wolves/cats require
     *       {@code isTamed} (so we tame the samples), {@code Sniffer} needs an idle state (the fresh
     *       default), pandas/foxes use the base love check.</li>
     * </ol>
     * Forcing love is faithful, not a cheat: the food gate already proved love is reachable. This is the
     * runtime counterpart of Mojang's hand-curated breedable list, and unlike {@code getBreedOffspring}
     * it correctly INCLUDES the egg-layers (frog/turtle/sniffer produce frogspawn/eggs via a custom
     * {@code spawnChildFromBreeding}, so their {@code getBreedOffspring} is {@code null}).
     *
     * <p>The conjunction lands exactly on the player-breedable set: the canMate gate drops
     * {@code mule}/{@code skeleton_horse}/{@code zombie_horse} (all inherit a {@code BreedGoal} but
     * {@code AbstractHorse.canMate} returns {@code false}), and the AI gate additionally drops
     * {@code zombie_nautilus} (passes canMate via forced love, but has no make-love behaviour). Hostile
     * breeders such as {@code hoglin} stay in — they carry {@code AnimalMakeLove}.
     */
    private static boolean isBreedable(EntityType<?> type, ServerLevel level, Mob mob) {
        // `a` is a FRESH sample (inLove == 0), so these read the species capability before we force state:
        //   canFallInLove() -> false  excludes mobs that can never enter love at all (happy_ghast);
        //   hasBreedingFood  -> false  excludes mobs with no breeding food (parrot, polar_bear);
        //   hasBreedingAi    -> false  excludes mobs whose AI never makes love (zombie_nautilus).
        if (!(mob instanceof Animal a) || !a.canFallInLove() || !hasBreedingFood(a) || !hasBreedingAi(mob)) {
            return false;
        }
        Entity partner = type.create(level, EntitySpawnReason.BREEDING);
        if (!(partner instanceof Animal b)) {
            if (partner != null) {
                partner.discard();
            }
            return false;
        }
        try {
            makeBreedingReady(a);
            makeBreedingReady(b);
            return a.canMate(b);  // honours sterility (mule) and taming/state rules in the species override
        } finally {
            b.discard();
        }
    }

    /** True if any registered item is a breeding food for this animal — i.e. it can be fed into love at
     *  all. Stateless (no love/tame needed), so it cleanly excludes the never-breedable {@link Animal}s
     *  ({@code Parrot}/{@code PolarBear}: {@code isFood} is always false). Iterating the item registry is
     *  fine here — {@code /aem dump} is a manual one-off, and the scan short-circuits on the first food. */
    private static boolean hasBreedingFood(Animal a) {
        for (var item : BuiltInRegistries.ITEM) {
            if (a.isFood(item.getDefaultInstance())) {
                return true;
            }
        }
        return false;
    }

    /** Put a throwaway sample into the maximal legitimate breeding state so {@link Animal#canMate} reports
     *  the species' real answer: tamed (horses/wolves/cats/camels only breed once tamed) and in love.
     *  {@code setTame(true, false)} / {@code setTamed(true)} set just the flag (skipping taming side
     *  effects), and {@code setInLoveTime} is a plain field write — all safe on an entity never added to
     *  the world. */
    private static void makeBreedingReady(Animal a) {
        if (a instanceof TamableAnimal t) {
            t.setTame(true, false);
        }
        if (a instanceof AbstractHorse h) {
            h.setTamed(true);
        }
        a.setInLoveTime(600);
    }

    /**
     * True if the mob's AI actually contains a breeding behaviour — the gate the forced-love
     * {@link Animal#canMate} check is blind to. Goal-driven animals carry a {@link BreedGoal} (incl. the
     * one inherited by {@code AbstractCow}/{@code AbstractHorse} and the {@code BreedGoal} subclasses used
     * by fox/panda/turtle); brain-driven animals (axolotl, frog, nautilus, hoglin, sniffer, …) carry an
     * {@link AnimalMakeLove} behaviour, often nested inside a {@link GateBehavior}/{@code RunOne}.
     * {@code zombie_nautilus} has neither — its brain only follows temptation — so this is what excludes it
     * while every real breeder (including hostile ones like hoglin) passes.
     *
     * <p>Neither the goal list nor the brain's behaviour map is publicly enumerable, so this reads them
     * reflectively (Mojang mappings: {@code Mob.goalSelector}, {@code Brain.availableBehaviorsByPriority},
     * {@code GateBehavior.behaviors}). On any reflection failure it conservatively returns {@code true}
     * (assume breedable) rather than silently dropping every brain-bred animal.
     */
    private static boolean hasBreedingAi(Mob mob) {
        try {
            Field goalField = Mob.class.getDeclaredField("goalSelector");
            goalField.setAccessible(true);
            GoalSelector goals = (GoalSelector) goalField.get(mob);
            for (WrappedGoal wrapped : goals.getAvailableGoals()) {
                if (wrapped.getGoal() instanceof BreedGoal) {
                    return true;
                }
            }

            Field brainField = Brain.class.getDeclaredField("availableBehaviorsByPriority");
            brainField.setAccessible(true);
            Map<?, ?> byPriority = (Map<?, ?>) brainField.get(mob.getBrain());
            for (Object byActivity : byPriority.values()) {
                for (Object behaviorSet : ((Map<?, ?>) byActivity).values()) {
                    for (Object behavior : (Iterable<?>) behaviorSet) {
                        if (behavior instanceof BehaviorControl<?> bc && containsMakeLove(bc)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            System.err.println("[AEM] entities dump: breeding-AI introspection failed for "
                    + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()) + " (" + exception + "); assuming breedable");
            return true;
        }
    }

    /** Recurse through {@link GateBehavior}/{@code RunOne} wrappers looking for an {@link AnimalMakeLove}
     *  leaf (brain breeders often wrap it a level or two deep). */
    private static boolean containsMakeLove(BehaviorControl<?> behavior) throws ReflectiveOperationException {
        if (behavior instanceof AnimalMakeLove) {
            return true;
        }
        if (behavior instanceof GateBehavior<?> gate) {
            Field behaviorsField = GateBehavior.class.getDeclaredField("behaviors");
            behaviorsField.setAccessible(true);
            for (Object child : (Iterable<?>) behaviorsField.get(gate)) {
                if (child instanceof BehaviorControl<?> bc && containsMakeLove(bc)) {
                    return true;
                }
            }
        }
        return false;
    }

    // -- region from biome spawn data ---------------------------------------

    /** What one walk of the biome spawn lists yields: each mob's region, and the biomes it spawns in. */
    private record SpawnData(Map<String, String> regions, Map<String, TreeSet<String>> biomes) {}

    /**
     * Read every {@code worldgen/biome/*.json}'s {@code spawners} and record, per entity, WHICH biomes
     * it naturally spawns in and hence which dimension: a mob's region is the shallowest spawning one
     * (Overworld &lt; Nether &lt; The End), classified via the {@code is_nether}/{@code is_end} biome tags.
     *
     * <p>The biome list is what the apworld needs to know that a mob is bound to one searchable place —
     * a mooshroom to Mushroom Fields, an axolotl to Lush Caves — which is the whole cost of obtaining
     * it, and what the Biome Finder exists to pay. That used to be a hand-written table of lambdas in
     * acquisition.py, which is how the mooshroom came to be missing from it.
     *
     * <p>Names are bare (no namespace), matching how this class already compares them to biome tags.
     * A mob with no natural spawn at all (the wither, a boat, a mob only a spawner or a structure
     * places) gets an empty list, which is not the same claim as "spawns everywhere".
     */
    private static SpawnData spawnData(ResourceManager rm) {
        Set<String> nether = biomesInTag(rm, "is_nether");
        Set<String> end = biomesInTag(rm, "is_end");

        // entity game_id -> {overworld?, nether?, end?}
        Map<String, boolean[]> hit = new java.util.HashMap<>();
        // entity game_id -> the biomes whose spawner list names it
        Map<String, TreeSet<String>> spawnBiomes = new TreeMap<>();
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
                    String mob = normalizeId(typeEl.getAsString());
                    boolean[] flags = hit.computeIfAbsent(mob, k -> new boolean[3]);
                    if (dim.equals("overworld")) flags[0] = true;
                    else if (dim.equals("nether")) flags[1] = true;
                    else flags[2] = true;
                    spawnBiomes.computeIfAbsent(mob, k -> new TreeSet<>()).add(biome);
                }
            }
        }

        Map<String, String> out = new java.util.HashMap<>();
        for (Map.Entry<String, boolean[]> e : hit.entrySet()) {
            boolean[] f = e.getValue();
            out.put(e.getKey(), f[0] ? "Overworld" : f[1] ? "Nether" : f[2] ? "The End" : "Overworld");
        }
        return new SpawnData(out, spawnBiomes);
    }

    /** A sorted json array of strings; an absent/empty set becomes an empty array, not null. */
    private static JsonArray stringArray(Set<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
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
