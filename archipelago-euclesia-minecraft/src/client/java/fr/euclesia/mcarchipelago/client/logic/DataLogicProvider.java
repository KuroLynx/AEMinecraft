package fr.euclesia.mcarchipelago.client.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.registry.APItemRegistry;
import fr.euclesia.mcarchipelago.registry.APLocationRegistry;
import net.minecraft.resources.Identifier;

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
 * <p>{@code GLITCHABLE} is not computed here yet; locations are classified as
 * {@link LogicState#CHECKED}, {@link LogicState#IN_LOGIC} or {@link LogicState#OUT_OF_LOGIC}.
 */
public final class DataLogicProvider implements LogicProvider {
    private JsonObject parsedFrom;        // slot_data["logic"] object identity last parsed
    private LogicGraph graph;             // parsed from parsedFrom (null if absent/invalid)
    private LogicEvaluation evaluation;   // memoized reachability pass
    private int evaluationVersion = -1;   // APItemRegistry version the pass was built for

    @Override
    public LogicState stateFor(Identifier advancementId) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        if (!client.state().isConnected()) {
            return LogicState.UNKNOWN;
        }
        LogicGraph graph = currentGraph(client);
        if (graph == null) {
            return LogicState.UNKNOWN;
        }

        String gameId = advancementId.toString();
        String locationName = graph.locationNameForGameId(gameId);
        if (locationName == null) {
            return LogicState.UNKNOWN; // not an Archipelago location for this slot
        }

        APLocationRegistry locations = client.registries().apLocations();
        Optional<Long> locationId = locations.idForGameId(gameId);
        if (locationId.isPresent() && locations.isChecked(locationId.get())) {
            return LogicState.CHECKED;
        }

        return currentEvaluation(client, graph).canReachLocation(locationName)
                ? LogicState.IN_LOGIC
                : LogicState.OUT_OF_LOGIC;
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
        int version = items.receivedVersion();
        if (evaluation == null || version != evaluationVersion) {
            evaluation = graph.newEvaluation(items::receivedCount);
            evaluationVersion = version;
        }
        return evaluation;
    }
}
