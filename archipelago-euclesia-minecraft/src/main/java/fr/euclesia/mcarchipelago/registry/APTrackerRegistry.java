package fr.euclesia.mcarchipelago.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Holds the Archipelago "tracker" advancements for the connected slot, parsed from
 * {@code slot_data["trackers"]} (see {@code minecraft/trackers.py}).
 *
 * <p>The mod ships a static datapack with an advancement per *possible* tracker (every mob
 * kill / boss kill / mob-spawn-unlock / structure-unlock) under the {@code aem} namespace. The
 * export here is *active-only* — it lists the tracker advancement ids that matter this seed and
 * links each to its Archipelago meaning. Two consumers use it:
 * <ul>
 *   <li>server visibility ({@code AdvancementVisibilityEvaluatorMixin}) — show only active
 *       trackers (plus the {@link #TAB_ROOT_ID tab root} when any exist), and</li>
 *   <li>client colouring ({@code DataLogicProvider}) — colour each tile from its linkage.</li>
 * </ul>
 */
public final class APTrackerRegistry {
    /** The main tab root advancement id; not itself an exported tracker. */
    public static final String TAB_ROOT_ID = "aem:archipelago";

    // Goal tiles on the main tab — native X/Y progress, rebuilt at runtime (RootAdvancementService).
    public static final String GOAL_ADVANCEMENTS_ID = "aem:goal/advancements";
    public static final String GOAL_BOSSES_ID = "aem:goal/bosses";

    // Category roots — each is its own advancement-screen tab; kept in sync with minecraft/trackers.py.
    public static final String CATEGORY_KILLS = "aem:category/kills";
    public static final String CATEGORY_ENTITY_UNLOCKS = "aem:category/entity_unlocks";
    public static final String CATEGORY_STRUCTURE_UNLOCKS = "aem:category/structure_unlocks";
    public static final String CATEGORY_KNOWLEDGE = "aem:category/knowledge";

    public static final String KIND_KILL = "kill";
    public static final String KIND_BOSS = "boss";
    public static final String KIND_UNLOCK = "unlock";

    /**
     * One tracker's linkage. {@code locationName}/{@code locationId} are set for kill/boss
     * (an AP location); {@code itemId} is set for unlocks (a received AP item). {@code count} is the
     * number of copies needed to satisfy an unlock tile — 1 for normal unlocks, N for level N of a
     * progressive item (e.g. Progressive Material Handling). {@code flags} is the unlock item's AP
     * classification as network flags, or {@code null} when the slot data predates it.
     */
    public record Tracker(String kind, String locationName, Long locationId, Long itemId, int count, Integer flags) {}

    private final Map<String, Tracker> byId = new HashMap<>();
    // Category sub-root ids that have ≥1 active tracker this seed (so empty branches stay hidden).
    private final Set<String> activeCategories = new HashSet<>();

    public void loadFromSlotData(JsonObject slotData) {
        byId.clear();
        activeCategories.clear();
        if (slotData == null || !slotData.has("trackers") || !slotData.get("trackers").isJsonObject()) {
            return;
        }
        JsonObject trackers = slotData.getAsJsonObject("trackers");
        for (Map.Entry<String, JsonElement> entry : trackers.entrySet()) {
            JsonObject data = entry.getValue().getAsJsonObject();
            byId.put(entry.getKey(), new Tracker(
                    data.get("kind").getAsString(),
                    data.has("location_name") ? data.get("location_name").getAsString() : null,
                    data.has("location_id") ? data.get("location_id").getAsLong() : null,
                    data.has("item_id") ? data.get("item_id").getAsLong() : null,
                    data.has("count") ? data.get("count").getAsInt() : 1,
                    data.has("flags") ? data.get("flags").getAsInt() : null));
            String category = categoryFor(entry.getKey());
            if (category != null) {
                activeCategories.add(category);
            }
        }
    }

    /** The category sub-root an active tracker advancement belongs to (by id prefix), or null. */
    private static String categoryFor(String trackerId) {
        if (trackerId.startsWith("aem:kill/") || trackerId.startsWith("aem:boss/")) {
            return CATEGORY_KILLS;
        }
        if (trackerId.startsWith("aem:unlock_mob/")) {
            return CATEGORY_ENTITY_UNLOCKS;
        }
        if (trackerId.startsWith("aem:unlock_structure/")) {
            return CATEGORY_STRUCTURE_UNLOCKS;
        }
        if (trackerId.startsWith("aem:unlock_knowledge/")) {
            return CATEGORY_KNOWLEDGE;
        }
        return null;
    }

    /** Whether {@code id} is a category sub-root that has any active tracker (should be shown). */
    public boolean isActiveCategory(String id) {
        return activeCategories.contains(id);
    }

    /** The tracker linkage for an advancement id, or {@code null} if it is not an active tracker. */
    public Tracker get(String advancementId) {
        return byId.get(advancementId);
    }

    /** Whether {@code advancementId} is an active tracker this seed. */
    public boolean isTracker(String advancementId) {
        return byId.containsKey(advancementId);
    }

    /** Whether the slot has any active tracker (i.e. the tracker tab should appear). */
    public boolean hasAny() {
        return !byId.isEmpty();
    }
}
