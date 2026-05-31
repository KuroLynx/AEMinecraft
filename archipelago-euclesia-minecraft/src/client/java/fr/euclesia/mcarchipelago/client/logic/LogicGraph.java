package fr.euclesia.mcarchipelago.client.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Archipelago logic graph for the connected slot, parsed from
 * {@code slot_data["logic"]} (see {@code minecraft/logic_export.py}).
 *
 * <p>Holds the region entrance graph and the per-location reachability rules, plus a
 * reverse map from Minecraft advancement id ({@code game_id}) to AP location name so
 * the renderer can look up a tile's logic state. Evaluation happens in a
 * {@link LogicEvaluation} obtained from {@link #newEvaluation(LogicEvaluation.ItemAvailability)}.
 */
public final class LogicGraph {
    /** An entrance edge: reaching {@code to} from the source region requires {@code rule}. */
    record Edge(String to, RuleNode rule) {}

    /** A location's reachability data: its region plus an extra {@code rule}. */
    record LocationEntry(String gameId, String region, RuleNode rule) {}

    private final String origin;
    private final Map<String, List<Edge>> regions;
    private final Map<String, LocationEntry> locations;
    private final Map<String, String> locationNameByGameId;

    private LogicGraph(String origin,
                       Map<String, List<Edge>> regions,
                       Map<String, LocationEntry> locations,
                       Map<String, String> locationNameByGameId) {
        this.origin = origin;
        this.regions = regions;
        this.locations = locations;
        this.locationNameByGameId = locationNameByGameId;
    }

    public static LogicGraph parse(JsonObject root) {
        String origin = root.get("origin").getAsString();

        Map<String, List<Edge>> regions = new HashMap<>();
        JsonObject regionsJson = root.getAsJsonObject("regions");
        for (Map.Entry<String, JsonElement> entry : regionsJson.entrySet()) {
            List<Edge> edges = new ArrayList<>();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                JsonObject edge = element.getAsJsonObject();
                edges.add(new Edge(edge.get("to").getAsString(), RuleNode.parse(edge.get("rule"))));
            }
            regions.put(entry.getKey(), edges);
        }

        Map<String, LocationEntry> locations = new HashMap<>();
        Map<String, String> locationNameByGameId = new HashMap<>();
        JsonObject locationsJson = root.getAsJsonObject("locations");
        for (Map.Entry<String, JsonElement> entry : locationsJson.entrySet()) {
            JsonObject loc = entry.getValue().getAsJsonObject();
            String gameId = loc.get("game_id").getAsString();
            locations.put(entry.getKey(), new LocationEntry(
                    gameId, loc.get("region").getAsString(), RuleNode.parse(loc.get("rule"))));
            locationNameByGameId.put(gameId, entry.getKey());
        }

        return new LogicGraph(origin, regions, locations, locationNameByGameId);
    }

    /** @return the AP location name for an advancement {@code game_id}, or {@code null} if none. */
    public String locationNameForGameId(String gameId) {
        return locationNameByGameId.get(gameId);
    }

    public LogicEvaluation newEvaluation(LogicEvaluation.ItemAvailability items) {
        return new LogicEvaluation(this, items);
    }

    String origin() {
        return origin;
    }

    Map<String, List<Edge>> regions() {
        return regions;
    }

    LocationEntry location(String name) {
        return locations.get(name);
    }
}
