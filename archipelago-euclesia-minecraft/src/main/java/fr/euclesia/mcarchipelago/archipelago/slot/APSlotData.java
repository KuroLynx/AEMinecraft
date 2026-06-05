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
        Map<Long, String> trapItems
) {
    /** A tool/armor pickup gate: the player needs {@code knowledge} AND {@code material} tiers. */
    public record ToolLock(String knowledge, int material) {}

    /**
     * What a filler item grants on receipt: either a fixed {@code count} of the Minecraft {@code item},
     * or — when {@code randomStacks > 0} — that many random vanilla item stacks ({@code item} unused).
     */
    public record FillerGrant(String item, int count, int randomStacks) {
        public boolean isRandom() {
            return randomStacks > 0;
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
                Map.of()
        );
    }

    public static APSlotData fromJson(JsonObject json) {
        return new APSlotData(
                WinningCondition.fromSlotValue(APJson.getInt(json, "goal", 0)),
                APJson.stringSet(json, "boss_list"),
                APJson.getBoolean(json, "death_link", false),
                APJson.getBoolean(json, "villager_trust", false),
                APJson.getBoolean(json, "kill_sanity", false),
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
                parseTrapItems(json)
        );
    }

    /** Parses {@code filler_items}: item id -> {"item": mc_id, "count": n} or {"random": n}. */
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
            int random = APJson.getInt(grant, "random", 0);
            if (random > 0) {
                filler.put(id, new FillerGrant(null, 0, random));
            } else {
                String item = APJson.getString(grant, "item", "");
                if (!item.isEmpty()) {
                    filler.put(id, new FillerGrant(item, Math.max(1, APJson.getInt(grant, "count", 1)), 0));
                }
            }
        }
        return Map.copyOf(filler);
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
