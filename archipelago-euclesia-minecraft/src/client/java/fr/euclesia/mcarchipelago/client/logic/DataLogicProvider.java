package fr.euclesia.mcarchipelago.client.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.registry.APItemRegistry;
import fr.euclesia.mcarchipelago.registry.APLocationRegistry;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * The real Phase-2 reachability source: interprets the logic graph shipped in
 * {@code slot_data["logic"]} against the items the connected slot has received, and maps
 * each advancement to a {@link LogicState} for the overlay.
 *
 * <p>Reading is lazy and cached. The graph is reparsed only when the slot-data {@code logic}
 * object is replaced (a (re)connect), and the memoized {@link LogicEvaluation} is dropped
 * whenever the received-item set changes (tracked by {@link APItemRegistry#receivedVersion()}).
 * All caching is touched only from the render thread that calls {@link #stateFor}.
 *
 * <p>{@code GLITCHABLE} comes from the export's permissive twin rule (see stateFrom); otherwise
 * {@link LogicState#CHECKED}, {@link LogicState#IN_LOGIC} or {@link LogicState#OUT_OF_LOGIC}.
 */
public final class DataLogicProvider implements LogicProvider {
    private JsonObject parsedFrom;        // slot_data["logic"] object identity last parsed
    private LogicGraph graph;             // parsed from parsedFrom (null if absent/invalid)
    private LogicEvaluation evaluation;   // memoized reachability pass
    private int evaluationVersion = -1;   // APItemRegistry version the pass was built for
    private int evaluationChecked = -1;   // APLocationRegistry checked version the pass was built for

    @Override
    public LogicState stateFor(Identifier advancementId) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        // Loaded locations rather than a live socket: in singleplayer an offline world restores the
        // same registries this reads, so the overlay keeps colouring instead of going dark the
        // moment the room becomes unreachable.
        if (!client.registries().apLocations().hasLocations()) {
            return LogicState.UNKNOWN;
        }

        String gameId = advancementId.toString();

        // Structural tiles of the main tab carry no logic meaning: the hub root and the category
        // tab-roots are never coloured (the goal tiles below are the only coloured main-tab tiles).
        if (gameId.equals(APTrackerRegistry.TAB_ROOT_ID) || isCategoryRoot(gameId)) {
            return LogicState.UNKNOWN;
        }

        // Goal tiles: coloured by how many of their targets are reachable / reached vs required —
        // red while fewer than required can be reached, green once enough are reachable, gray once
        // enough have been reached. Driven by the same logic evaluation as the per-location tiles.
        if (gameId.equals(APTrackerRegistry.GOAL_ADVANCEMENTS_ID)) {
            return goalAdvancementsState(client);
        }
        if (gameId.equals(APTrackerRegistry.GOAL_BOSSES_ID)) {
            return goalBossesState(client);
        }

        // Tracker-tab tiles (aem:*) are coloured from their AP linkage, not the logic graph.
        APTrackerRegistry.Tracker tracker = client.registries().apTrackers().get(gameId);
        if (tracker != null) {
            return trackerState(client, tracker);
        }

        LogicGraph graph = currentGraph(client);
        if (graph == null) {
            return LogicState.UNKNOWN;
        }

        String locationName = graph.locationNameForGameId(gameId);
        if (locationName == null) {
            return LogicState.UNKNOWN; // not an Archipelago location for this slot
        }

        APLocationRegistry locations = client.registries().apLocations();
        Optional<Long> locationId = locations.idForGameId(gameId);
        if (locationId.isPresent() && locations.isChecked(locationId.get())) {
            return LogicState.CHECKED;
        }

        return stateFrom(currentEvaluation(client, graph), locationName);
    }

    /**
     * Green when strict logic reaches it, YELLOW when only a route the randomizer refused to count on
     * does (a rare drop, a non-progression structure's chest, a Wandering Trader), red otherwise.
     * The yellow tier is absent unless the seed enabled glitch_logic — with it off no permissive rule
     * ships, isGlitchable is always false, and tiles read green/red exactly as before.
     */
    private static LogicState stateFrom(LogicEvaluation evaluation, String locationName) {
        if (evaluation.canReachLocation(locationName)) {
            return LogicState.IN_LOGIC;
        }
        return evaluation.isGlitchable(locationName)
                ? LogicState.GLITCHABLE
                : LogicState.OUT_OF_LOGIC;
    }

    /**
     * Colour a tracker-tab tile. Unlock tiles are AP items: green once received, red otherwise.
     * Kill/boss tiles are AP locations: gray when checked, else green/red by reachability (reusing
     * the same logic evaluation as advancement tiles).
     */
    private LogicState trackerState(ArchipelagoClient client, APTrackerRegistry.Tracker tracker) {
        if (APTrackerRegistry.KIND_UNLOCK.equals(tracker.kind())) {
            Long itemId = tracker.itemId();
            // Progressive tiles need `count` copies (level N); normal unlocks need 1.
            boolean received = itemId != null
                    && client.registries().apItems().receivedCount(itemId) >= tracker.count();
            // Received = obtained: gray it out like a checked location. Not yet received = red.
            return received ? LogicState.CHECKED : LogicState.OUT_OF_LOGIC;
        }

        APLocationRegistry locations = client.registries().apLocations();
        Long locationId = tracker.locationId();
        if (locationId != null && locations.isChecked(locationId)) {
            return LogicState.CHECKED;
        }

        LogicGraph graph = currentGraph(client);
        String locationName = tracker.locationName();
        if (graph == null || locationName == null) {
            return LogicState.OUT_OF_LOGIC;
        }
        return stateFrom(currentEvaluation(client, graph), locationName);
    }

    private static boolean isCategoryRoot(String gameId) {
        return gameId.equals(APTrackerRegistry.CATEGORY_KILLS)
                || gameId.equals(APTrackerRegistry.CATEGORY_ENTITY_UNLOCKS)
                || gameId.equals(APTrackerRegistry.CATEGORY_STRUCTURE_UNLOCKS)
                || gameId.equals(APTrackerRegistry.CATEGORY_KNOWLEDGE);
    }

    /**
     * Colour for a goal tile from its counts. {@code required <= 0} means there is nothing to do for
     * this goal, so it stays neutral (no tint). Otherwise: gray once enough targets are reached,
     * green once enough are reachable, red while fewer than required can be reached.
     */
    private static LogicState goalState(int reachable, int reached, int required) {
        if (required <= 0) {
            return LogicState.UNKNOWN;
        }
        if (reached >= required) {
            return LogicState.CHECKED;
        }
        return reachable >= required ? LogicState.IN_LOGIC : LogicState.OUT_OF_LOGIC;
    }

    /** Counts over every "Advancement: …" location: how many are reachable and how many are checked. */
    private LogicState goalAdvancementsState(ArchipelagoClient client) {
        LogicGraph graph = currentGraph(client);
        if (graph == null) {
            return LogicState.UNKNOWN;
        }
        LogicEvaluation evaluation = currentEvaluation(client, graph);
        APLocationRegistry locations = client.registries().apLocations();
        int reachable = 0;
        int reached = 0;
        int total = 0;
        for (Map.Entry<String, LogicGraph.LocationEntry> entry : graph.locations().entrySet()) {
            if (!entry.getKey().startsWith("Advancement: ")) {
                continue;
            }
            total++;
            if (evaluation.canReachLocation(entry.getKey())) {
                reachable++;
            }
            if (isChecked(locations, entry.getValue().gameId())) {
                reached++;
            }
        }
        // The goal can never need more advancements than exist this seed (mirrors the apworld clamp).
        int required = Math.min(intFromSlotData(client, "advancements_required"), total);
        return goalState(reachable, reached, required);
    }

    /** Counts over the slot's {@code boss_list}: how many goal bosses are reachable and killed. */
    private LogicState goalBossesState(ArchipelagoClient client) {
        LogicGraph graph = currentGraph(client);
        if (graph == null) {
            return LogicState.UNKNOWN;
        }
        JsonObject slotData = client.state().slotData();
        if (slotData == null || !slotData.has("boss_list") || !slotData.get("boss_list").isJsonArray()) {
            return LogicState.UNKNOWN;
        }
        JsonArray bossList = slotData.getAsJsonArray("boss_list");
        LogicEvaluation evaluation = currentEvaluation(client, graph);
        APLocationRegistry locations = client.registries().apLocations();
        int reachable = 0;
        int reached = 0;
        for (JsonElement element : bossList) {
            String bossGameId = element.getAsString();
            String locationName = graph.locationNameForGameId(bossGameId);
            if (locationName != null && evaluation.canReachLocation(locationName)) {
                reachable++;
            }
            if (isChecked(locations, bossGameId)) {
                reached++;
            }
        }
        return goalState(reachable, reached, bossList.size());
    }

    private static boolean isChecked(APLocationRegistry locations, String gameId) {
        Optional<Long> id = locations.idForGameId(gameId);
        return id.isPresent() && locations.isChecked(id.get());
    }

    private static int intFromSlotData(ArchipelagoClient client, String key) {
        JsonObject slotData = client.state().slotData();
        if (slotData == null || !slotData.has(key) || !slotData.get(key).isJsonPrimitive()) {
            return 0;
        }
        return slotData.get(key).getAsInt();
    }

    private LogicGraph currentGraph(ArchipelagoClient client) {
        JsonObject slotData = client.state().slotData();
        JsonElement logic = slotData == null ? null : slotData.get("logic");
        if (logic == null || !logic.isJsonObject()) {
            parsedFrom = null;
            graph = null;
            evaluation = null;
            return null;
        }
        JsonObject logicObject = logic.getAsJsonObject();
        if (logicObject != parsedFrom) { // slot data replaced -> reparse and drop stale pass
            try {
                graph = LogicGraph.parse(logicObject);
            } catch (RuntimeException exception) {
                AEM.LOGGER.error("Failed to parse Archipelago logic export", exception);
                graph = null;
            }
            parsedFrom = logicObject;
            evaluation = null;
        }
        return graph;
    }

    private LogicEvaluation currentEvaluation(ArchipelagoClient client, LogicGraph graph) {
        APItemRegistry items = client.registries().apItems();
        APLocationRegistry locations = client.registries().apLocations();
        // Checked locations feed the pass (LogicEvaluation.solve), so a check invalidates it just as
        // an item does. Watching receivedVersion alone left the tracker showing pre-check colours
        // until the next item happened to land.
        int version = items.receivedVersion();
        int checked = locations.checkedVersion();
        if (evaluation == null || version != evaluationVersion || checked != evaluationChecked) {
            evaluation = graph.newEvaluation(items::receivedCount,
                    name -> locations.idForName(name).map(locations::isChecked).orElse(false));
            evaluationVersion = version;
            evaluationChecked = checked;
        }
        return evaluation;
    }
}
