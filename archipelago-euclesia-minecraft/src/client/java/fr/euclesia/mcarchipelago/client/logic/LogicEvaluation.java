package fr.euclesia.mcarchipelago.client.logic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One reachability pass over a {@link LogicGraph} for a fixed snapshot of received items.
 *
 * <p>Region and location reachability is the <em>least fixed point</em> of the logic graph.
 * The rules are monotone ({@code const}/{@code has}/{@code region}/{@code loc}/{@code and}/
 * {@code or} — no negation), so we start with everything unreachable (only the origin region
 * reachable) and re-evaluate every edge and location until a full pass flips nothing new to
 * reachable. This mirrors {@code ExportEvaluator} in {@code tools/logic_selfcheck.py}, which is
 * validated against Archipelago's own {@code CollectionState} (its monotonic region sweep).
 *
 * <p>A naive memoised DFS with a cycle guard ({@code cache.put(region, false)} while a region is
 * in progress) is <strong>not</strong> correct here: it caches the temporary {@code false} into
 * sibling locations evaluated mid-cycle, producing order-dependent false negatives. Concretely,
 * the Nether entrance depends on reaching "Ice Bucket Challenge", whose rule ORs several
 * Nether-region locations before an always-true Overworld branch — so those locations get cached
 * unreachable while the Nether is still resolving, and never recover. The fixed point below
 * resolves the cycle the same way Archipelago does.
 *
 * <p>Inputs are RECEIVED items only (counts); unchecked locations are never swept. A new pass
 * must be created whenever the received-item set changes (see {@link DataLogicProvider}).
 */
public final class LogicEvaluation {
    /** Supplies the number of copies of an item the slot has received. */
    @FunctionalInterface
    public interface ItemAvailability {
        int count(String itemName);
    }

    private final LogicGraph graph;
    private final ItemAvailability items;
    private final Map<String, Boolean> regionReach = new HashMap<>();
    private final Map<String, Boolean> locationReach = new HashMap<>();

    LogicEvaluation(LogicGraph graph, ItemAvailability items) {
        this.graph = graph;
        this.items = items;
        solve();
    }

    public boolean has(String item, int count) {
        return items.count(item) >= count;
    }

    /** Reads the solved snapshot; {@link RuleNode.Region} calls this during {@link #solve}. */
    public boolean canReachRegion(String region) {
        return Boolean.TRUE.equals(regionReach.get(region));
    }

    /** Reads the solved snapshot; {@link RuleNode.Loc} calls this during {@link #solve}. */
    public boolean canReachLocation(String name) {
        return Boolean.TRUE.equals(locationReach.get(name));
    }

    private void solve() {
        regionReach.put(graph.origin(), true);
        boolean changed = true;
        while (changed) { // monotone ⇒ terminates (a pass can only flip false → true)
            changed = false;
            for (Map.Entry<String, List<LogicGraph.Edge>> entry : graph.regions().entrySet()) {
                String from = entry.getKey();
                for (LogicGraph.Edge edge : entry.getValue()) {
                    if (!canReachRegion(edge.to())
                            && canReachRegion(from)
                            && edge.rule().eval(this)) {
                        regionReach.put(edge.to(), true);
                        changed = true;
                    }
                }
            }
            for (Map.Entry<String, LogicGraph.LocationEntry> entry : graph.locations().entrySet()) {
                LogicGraph.LocationEntry loc = entry.getValue();
                if (!canReachLocation(entry.getKey())
                        && canReachRegion(loc.region())
                        && loc.rule().eval(this)) {
                    locationReach.put(entry.getKey(), true);
                    changed = true;
                }
            }
        }
    }
}
