package fr.euclesia.mcarchipelago.archipelago.slot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.engine.rules.WinningCondition;
import fr.euclesia.mcarchipelago.protocol.APJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record APSlotData(
        WinningCondition goal,
        Set<String> bossSelection,
        boolean deathLink,
        boolean villagerTrust,
        boolean killSanity,
        boolean structureFinderActive,
        boolean blazeandcave,
        boolean bacapRewards,
        boolean deathList,
        int deathListCount,
        int advancementsRequired,
        String startDimension,
        Set<String> mobSpawnLock,
        Map<Long, String> itemNamesById,
        Map<String, Long> locationIdsByGameId,
        Map<String, Long> trackedMobs,
        List<String> deathListMobs,
        Map<String, Long> mobSpawnLockMobs,
        Map<String, Long> structureLocks,
        Map<String, Long> materialHandlingLocks,
        Map<String, ToolLock> toolLocks,
        Map<String, String> stationKnowledgeLocks,
        Map<String, String> dimensionLocks,
        Map<Long, FillerGrant> fillerItems,
        Map<Long, String> trapItems,
        List<ContentRequirement> requiredContent,
        ItemGateBehavior itemGateBehavior,
        int slotDataVersion
) {
    /** A tool/armor pickup gate: the player needs {@code knowledge} AND {@code material} tiers. */
    public record ToolLock(String knowledge, int material) {}

    /**
     * Per-route handling of a still-locked item (see {@code item_gate_behavior} / the Python
     * {@code ItemGateBehavior} option). Each flag is {@code true} when that route is gated (the item is
     * blocked until it unlocks) and {@code false} when the route is left open:
     * <ul>
     *   <li>{@code crafting} — taking it out of a crafting grid: the crafting table or the player's own
     *       2x2 inventory grid ({@code SlotMixin});</li>
     *   <li>{@code station} — taking it out of a workstation GUI that makes or transforms items: furnace
     *       family, anvil, smithing table, grindstone, stonecutter, loom, cartography table, brewing
     *       stand, enchanting table, villager trade, crafter ({@code SlotMixin});</li>
     *   <li>{@code container} — taking it out of any other GUI, i.e. plain storage: chest, barrel,
     *       shulker box, hopper, dispenser, ender chest, minecart/mount inventories
     *       ({@code SlotMixin});</li>
     *   <li>{@code pickup} — picking it up off the ground ({@code ItemEntityMixin});</li>
     *   <li>{@code given} — the {@code /give} command handing it over ({@code GiveCommandMixin}).</li>
     * </ul>
     * {@link #DEFAULT} matches the historical behavior for slot data that predates this field.
     */
    public record ItemGateBehavior(boolean crafting, boolean station, boolean container, boolean pickup,
                                   boolean given) {
        public static final ItemGateBehavior DEFAULT = new ItemGateBehavior(true, true, true, true, false);
    }

    /**
     * What a filler item grants on receipt — exactly one of two shapes:
     * <ul>
     *   <li>a temporary {@code buff} the mod reproduces mechanically (see {@code FillerBuffService}),
     *       lasting {@code seconds} per received copy (duration stacks); or</li>
     *   <li>a Minecraft {@code item} stack ({@code "minecraft:dirt"}) of {@code count} handed straight
     *       into the inventory (see {@code FillerItemService}).</li>
     * </ul>
     * The unused half is empty/zero; {@link #isBuff()} / {@link #isItem()} pick the branch.
     */
    public record FillerGrant(String buff, int seconds, String item, int count) {
        public boolean isBuff() { return buff != null && !buff.isEmpty(); }
        public boolean isItem() { return item != null && !item.isEmpty(); }
    }

    /**
     * A datapack/mod this seed requires (see {@code required_content}). {@code kind} is
     * {@code "datapack"} or {@code "mod"}; {@code id} is the pack namespace / Fabric mod id;
     * {@code name} is the human display name; {@code version} is the required version; {@code match}
     * is a lowercase substring identifying the datapack among the installed ones.
     */
    public record ContentRequirement(String kind, String id, String name, String version, String match) {
        public boolean isMod() {
            return "mod".equals(kind);
        }
    }

    public static APSlotData empty() {
        return new APSlotData(
                WinningCondition.KILL_ENDER_DRAGON,
                Set.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                0,
                "overworld",
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                ItemGateBehavior.DEFAULT,
                0
        );
    }

    public static APSlotData fromJson(JsonObject json) {
        return new APSlotData(
                WinningCondition.fromSlotValue(APJson.getInt(json, "goal", 0)),
                APJson.stringSet(json, "boss_list"),
                APJson.getBoolean(json, "death_link", false),
                APJson.getBoolean(json, "villager_trust", false),
                APJson.getBoolean(json, "kill_sanity", false),
                // Absent in older slot data -> default enabled so existing seeds keep the finder.
                APJson.getBoolean(json, "structure_finder", true),
                // BACAP: pack active + whether its item/XP rewards stay on (see BacapConfigService).
                APJson.getBoolean(json, "blazeandcave", false),
                APJson.getBoolean(json, "bacap_rewards", false),
                APJson.getBoolean(json, "death_list", false),
                APJson.getInt(json, "death_list_count", 0),
                APJson.getInt(json, "advancements_required", 0),
                APJson.getString(json, "start_dimension", "overworld"),
                APJson.stringSet(json, "mob_spawn_lock"),
                APJson.longStringMap(json, "items"),
                APJson.stringLongMap(json, "locations"),
                APJson.stringLongMap(json, "tracked_mobs"),
                APJson.stringList(json, "death_list_mobs"),
                APJson.stringLongMap(json, "mob_spawn_lock_mobs"),
                APJson.stringLongMap(json, "structure_locks"),
                APJson.stringLongMap(json, "material_handling_locks"),
                parseToolLocks(json),
                APJson.stringStringMap(json, "station_knowledge_locks"),
                APJson.stringStringMap(json, "dimension_locks"),
                parseFillerItems(json),
                parseTrapItems(json),
                parseRequiredContent(json),
                parseItemGateBehavior(json),
                // Absent (0) in pre-versioning slot data -> treated as legacy/unversioned by
                // CompatibilityService (allowed with a warning, not blocked).
                APJson.getInt(json, "slot_data_version", 0)
        );
    }

    /**
     * Parses {@code filler_items}: item id -> a buff grant {@code {"buff": key, "seconds": n}} or an
     * item grant {@code {"item": "minecraft:dirt", "count": n}} (see {@code minecraft_aem/filler.py}).
     */
    private static Map<Long, FillerGrant> parseFillerItems(JsonObject json) {
        JsonElement element = json.get("filler_items");
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<Long, FillerGrant> filler = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            Long id = parseLongKey(entry.getKey());
            if (id == null) {
                continue;
            }
            JsonObject grant = entry.getValue().getAsJsonObject();
            String buff = APJson.getString(grant, "buff", "");
            if (!buff.isEmpty()) {
                filler.put(id, new FillerGrant(buff, Math.max(1, APJson.getInt(grant, "seconds", 30)), "", 0));
                continue;
            }
            String item = APJson.getString(grant, "item", "");
            if (!item.isEmpty()) {
                filler.put(id, new FillerGrant("", 0, item, Math.max(1, APJson.getInt(grant, "count", 1))));
            }
        }
        return Map.copyOf(filler);
    }

    /** Parses {@code required_content}: list of {kind, id, name, version, match}. */
    private static List<ContentRequirement> parseRequiredContent(JsonObject json) {
        JsonElement element = json.get("required_content");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<ContentRequirement> requirements = new java.util.ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject req = entry.getAsJsonObject();
            String id = APJson.getString(req, "id", "");
            if (id.isEmpty()) {
                continue;
            }
            requirements.add(new ContentRequirement(
                    APJson.getString(req, "kind", "datapack"),
                    id,
                    APJson.getString(req, "name", id),
                    APJson.getString(req, "version", ""),
                    APJson.getString(req, "match", id).toLowerCase(java.util.Locale.ROOT)));
        }
        return List.copyOf(requirements);
    }

    /**
     * Parses {@code item_gate_behavior}: {@code {crafting|station|container|pickup|given: true|false}}
     * (true = gated). A missing object or key falls back to {@link ItemGateBehavior#DEFAULT} (every GUI
     * route and pickup gated, /give open) so slot data written before this field keeps the old behavior.
     *
     * <p>{@code station} and {@code container} are newer than {@code crafting}: slot data from before the
     * GUI routes were split carries a single {@code crafting} flag that covered every container take, so
     * when they are absent they inherit whatever {@code crafting} says rather than the default.
     */
    private static ItemGateBehavior parseItemGateBehavior(JsonObject json) {
        JsonElement element = json.get("item_gate_behavior");
        if (element == null || !element.isJsonObject()) {
            return ItemGateBehavior.DEFAULT;
        }
        JsonObject routes = element.getAsJsonObject();
        ItemGateBehavior fallback = ItemGateBehavior.DEFAULT;
        boolean crafting = APJson.getBoolean(routes, "crafting", fallback.crafting());
        return new ItemGateBehavior(
                crafting,
                APJson.getBoolean(routes, "station", crafting),
                APJson.getBoolean(routes, "container", crafting),
                APJson.getBoolean(routes, "pickup", fallback.pickup()),
                APJson.getBoolean(routes, "given", fallback.given()));
    }

    /** Parses {@code trap_items}: item id -> effect key. */
    private static Map<Long, String> parseTrapItems(JsonObject json) {
        JsonElement element = json.get("trap_items");
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<Long, String> traps = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            Long id = parseLongKey(entry.getKey());
            if (id != null && entry.getValue().isJsonPrimitive()) {
                traps.put(id, entry.getValue().getAsString());
            }
        }
        return Map.copyOf(traps);
    }

    private static Long parseLongKey(String key) {
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Parses {@code tool_locks}: item id -> {"knowledge": "Knowledge: X", "material": tier}. */
    private static Map<String, ToolLock> parseToolLocks(JsonObject json) {
        JsonElement element = json.get("tool_locks");
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<String, ToolLock> locks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject lock = entry.getValue().getAsJsonObject();
            String knowledge = APJson.getString(lock, "knowledge", "");
            int material = APJson.getInt(lock, "material", 0);
            if (!knowledge.isEmpty()) {
                locks.put(entry.getKey(), new ToolLock(knowledge, material));
            }
        }
        return Map.copyOf(locks);
    }
}
