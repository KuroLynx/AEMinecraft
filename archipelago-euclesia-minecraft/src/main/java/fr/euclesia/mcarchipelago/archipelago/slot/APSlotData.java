package fr.euclesia.mcarchipelago.archipelago.slot;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.engine.rules.WinningCondition;
import fr.euclesia.mcarchipelago.protocol.APJson;

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
        Map<String, Long> structureLocks
) {
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
                Map.of()
        );
    }

    public static APSlotData fromJson(JsonObject json) {
        return new APSlotData(
                WinningCondition.fromSlotValue(APJson.getInt(json, "goal", 0)),
                APJson.stringSet(json, "boss_selection"),
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
                APJson.stringLongMap(json, "structure_locks")
        );
    }
}
