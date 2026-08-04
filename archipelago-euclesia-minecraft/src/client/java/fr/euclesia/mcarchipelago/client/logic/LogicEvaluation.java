package fr.euclesia.mcarchipelago.client.logic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final Set<Integer> resolvingRefs = new HashSet<>();

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

    /**
     * Whether {@code name} is reachable by a route the randomizer refused to count on — a rare drop,
     * a chest in a structure the seed doesn't treat as progression, a Wandering Trader (see
     * {@code RuleHelper._demote} in the apworld). False when the seed shipped no permissive rule for
     * this location, which is the usual case: the two graphs agree almost everywhere.
     *
     * <p>Evaluated against the SOLVED strict snapshot rather than a second fixed point of its own.
     * That is deliberate and slightly conservative: a glitch route is judged on regions and locations
     * strict logic already grants, so glitch routes never chain into one another. Anything it does
     * report is genuinely reachable; it just won't find a route that needs two glitches in sequence.
     */
    public boolean isGlitchable(String name) {
        RuleNode rule = graph.glitchRule(name);
        return rule != null && rule.eval(this);
    }

    /**
     * Evaluate a shared subtree behind a {@code ref}. Definitions are acyclic by construction
     * (the exporter only hoists subtrees of the acyclic rule forest), so the guard below is purely
     * defensive: a re-entered id means a cycle slipped through, which resolves to {@code false}
     * (unreachable) rather than recursing forever. Results are NOT cached — region/location
     * reachability flips during a {@link #solve} pass, so a ref must re-evaluate each time exactly
     * like the inlined subtree it replaced.
     */
    boolean evalRef(int id) {
        RuleNode definition = graph.definition(id);
        if (definition == null || !resolvingRefs.add(id)) {
            return false;
        }
        try {
            return definition.eval(this);
        } finally {
            resolvingRefs.remove(id);
        }
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
