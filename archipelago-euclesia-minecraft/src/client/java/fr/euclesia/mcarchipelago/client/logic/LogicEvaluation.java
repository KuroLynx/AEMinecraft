package fr.euclesia.mcarchipelago.client.logic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One reachability pass over a {@link LogicGraph} for a fixed snapshot of received items.
 *
 * <p>Region and location reachability are memoized (with cycle guards) so a whole
 * advancement screen — hundreds of tiles — can be evaluated while traversing the region
 * graph only once. This mirrors {@code ExportEvaluator} in {@code tools/logic_selfcheck.py},
 * which is validated against Archipelago's own {@code CollectionState}.
 *
 * <p>Inputs are RECEIVED items only (counts); unchecked locations are never swept. A new
 * pass must be created whenever the received-item set changes (see {@link DataLogicProvider}).
 */
public final class LogicEvaluation {
    /** Supplies the number of copies of an item the slot has received. */
    @FunctionalInterface
    public interface ItemAvailability {
        int count(String itemName);
    }

    private final LogicGraph graph;
    private final ItemAvailability items;
    private final Map<String, Boolean> regionCache = new HashMap<>();
    private final Map<String, Boolean> locationCache = new HashMap<>();

    LogicEvaluation(LogicGraph graph, ItemAvailability items) {
        this.graph = graph;
        this.items = items;
    }

    public boolean has(String item, int count) {
        return items.count(item) >= count;
    }

    public boolean canReachRegion(String region) {
        if (region.equals(graph.origin())) {
            return true;
        }
        Boolean cached = regionCache.get(region);
        if (cached != null) {
            return cached;
        }
        regionCache.put(region, false); // cycle guard: in-progress regions read as unreachable
        boolean result = false;
        search:
        for (Map.Entry<String, List<LogicGraph.Edge>> entry : graph.regions().entrySet()) {
            for (LogicGraph.Edge edge : entry.getValue()) {
                if (edge.to().equals(region)
                        && canReachRegion(entry.getKey())
                        && edge.rule().eval(this)) {
                    result = true;
                    break search;
                }
            }
        }
        regionCache.put(region, result);
        return result;
    }

    public boolean canReachLocation(String name) {
        Boolean cached = locationCache.get(name);
        if (cached != null) {
            return cached;
        }
        locationCache.put(name, false); // cycle guard
        LogicGraph.LocationEntry loc = graph.location(name);
        if (loc == null) {
            return false;
        }
        boolean result = canReachRegion(loc.region()) && loc.rule().eval(this);
        locationCache.put(name, result);
        return result;
    }
}
