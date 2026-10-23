package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.mixin.ServerAdvancementManagerAccessor;
import fr.euclesia.mcarchipelago.registry.APLocationRegistry;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.TreeNodePosition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Closes the holes the seed punches in the tracker tabs.
 *
 * <p>The tracker datapack lays its tiles out as a grid the only way the advancement screen allows:
 * by tree topology. A row is a parent→child chain, so tile 4 sits to the right of tile 3 because it
 * is tile 3's child, and each row's first tile hangs off the category root. Which tiles are shown,
 * though, is decided per seed — {@code AdvancementVisibilityEvaluatorMixin} reveals only the trackers
 * in {@code slot_data["trackers"]} — and the client never receives the rest. An advancement whose
 * parent never arrived cannot be inserted into the client's tree, so it does not render at all.
 *
 * <p>The two together mean one inactive tile silently swallows the whole rest of its row. It is not
 * hypothetical: the Knowledge tab's row of Dimension Unlocks and tool gates runs
 * {@code nether → the_end → overworld → biome_finder → sword → spear → shovel → axe}, and the start
 * dimension's own unlock is never in the pool (you already spawn there) — so on every Overworld start
 * the {@code overworld} tile is hidden and takes the five tiles behind it with it.
 *
 * <p>So the chains are compacted at runtime, once the active set is known: every active tile is
 * re-parented onto the nearest ancestor that is actually visible — the previous active tile in its
 * row, or the category root when everything before it is gone. Rows keep their order and simply close
 * up; a row with nothing active disappears entirely, which is what it should do. Inactive tiles are
 * left exactly as they are: nothing visible hangs off them any more, and they are never sent.
 *
 * <p>Advancement checks get the same treatment: a check only shows when it is an active location, so
 * one under a non-check (with BACAP + challenge_sanity frames, How Did We Get Here? sits under the
 * hidden Global Vaccination) showed nowhere. It hangs off its nearest visible ancestor instead.
 *
 * <p>The Structure Unlocks tab goes one step further and is laid out again as a full grid of the
 * active tiles: whether a structure is locked, and whether its biome variants share one group tile,
 * are both per seed, so closing up rows alone leaves a handful of short rows (a grouped seed hides
 * every Village/Ruined Portal/… member tile).
 *
 * <p>Same swap-and-rebuild as {@link RootAdvancementService} (which does this to give the goal tiles
 * their criteria), and idempotent, so joins can re-run it freely. It rewrites the loaded advancements
 * rather than the datapack, so a {@code /reload} restores the uncompacted chains until the next run.
 */
public final class TrackerLayoutService {
    /** Tiles per row of a re-gridded tab; matches ROW_WIDTH in tools/gen_tracker_advancements.py. */
    private static final int ROW_WIDTH = 8;
    private static final String STRUCTURE_TILE_PREFIX = "unlock_structure/";
    private static final String STRUCTURE_GROUP_PREFIX = STRUCTURE_TILE_PREFIX + "group/";

    private TrackerLayoutService() {}

    /**
     * Re-parents every active tracker tile onto its nearest visible ancestor. Must run on the server
     * thread, and before the clients' advancements are reloaded. No-op until the slot's tracker set is
     * known, and after the first pass (the parents already point at visible tiles).
     */
    public static void compact(MinecraftServer server) {
        APTrackerRegistry trackers = AEM.ARCHIPELAGO.client().registries().apTrackers();
        APLocationRegistry locations = AEM.ARCHIPELAGO.client().registries().apLocations();
        if (!trackers.hasAny()) {
            return;  // no slot data yet: everything is revealed, so no chain can be broken
        }
        ServerAdvancementManager manager = server.getAdvancements();
        Map<Identifier, AdvancementHolder> changed = new HashMap<>();
        Map<Identifier, Identifier> gridParents = structureGrid(manager, trackers);
        for (AdvancementHolder holder : manager.getAllAdvancements()) {
            String id = holder.id().toString();
            if (!trackers.isTracker(id) && !locations.isActiveLocation(id)) {
                continue;  // roots keep their place; hidden tiles are nobody's parent once we are done
            }
            Identifier parent = gridParents.containsKey(holder.id())
                    ? gridParents.get(holder.id())
                    : nearestVisible(manager, trackers, locations, holder);
            if (parent == null || parent.equals(holder.value().parent().orElse(null))) {
                continue;
            }
            changed.put(holder.id(), reparent(holder, parent));
        }
        if (changed.isEmpty()) {
            return;
        }

        install(manager, changed);
        AEM.LOGGER.info("Compacted {} tracker / check tiles onto visible parents", changed.size());
    }

    /**
     * The active Structure Unlock tiles as rows of {@link #ROW_WIDTH}: each tile's parent is the one
     * before it, a row's first tile hangs off the category root. Ordered by structure name, a group
     * tile ({@code group/village}) among its variants, like the datapack.
     */
    private static Map<Identifier, Identifier> structureGrid(ServerAdvancementManager manager,
                                                             APTrackerRegistry trackers) {
        List<Identifier> tiles = manager.getAllAdvancements().stream()
                .map(AdvancementHolder::id)
                .filter(id -> id.getPath().startsWith(STRUCTURE_TILE_PREFIX) && trackers.isTracker(id.toString()))
                .sorted(Comparator.comparing(id -> id.getPath().replace(STRUCTURE_GROUP_PREFIX, STRUCTURE_TILE_PREFIX)))
                .toList();
        Identifier root = Identifier.parse(APTrackerRegistry.CATEGORY_STRUCTURE_UNLOCKS);
        Map<Identifier, Identifier> parents = new HashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            parents.put(tiles.get(i), i % ROW_WIDTH == 0 ? root : tiles.get(i - 1));
        }
        return parents;
    }

    /**
     * Swaps {@code changed} into the manager's (immutable) advancement map, rebuilds the tree in place
     * and lays it out again. The layout matters from 26.3 on: tile positions live on the tree nodes
     * there, so freshly added nodes would all sit at 0,0 without it.
     */
    static void install(ServerAdvancementManager manager, Map<Identifier, AdvancementHolder> changed) {
        ServerAdvancementManagerAccessor accessor = (ServerAdvancementManagerAccessor) manager;
        Map<Identifier, AdvancementHolder> updated = new HashMap<>(accessor.archipelago_euclesia$getAdvancements());
        updated.putAll(changed);
        accessor.archipelago_euclesia$setAdvancements(Map.copyOf(updated));

        AdvancementTree tree = manager.tree();
        tree.clear();
        tree.addAll(accessor.archipelago_euclesia$getAdvancements().values());
        for (AdvancementNode root : tree.roots()) {
            if (root.holder().value().display().isPresent()) {
                TreeNodePosition.run(root);
            }
        }
    }

    /**
     * Walks up from {@code holder} to the first ancestor this seed actually shows: an active tracker
     * tile, a category root with any active tracker, or the main tab's own tiles. {@code null} if the
     * chain runs out (a parent missing from the pack), leaving that tile alone.
     */
    private static Identifier nearestVisible(ServerAdvancementManager manager, APTrackerRegistry trackers,
                                             APLocationRegistry locations, AdvancementHolder holder) {
        Optional<Identifier> parent = holder.value().parent();
        while (parent.isPresent()) {
            Identifier id = parent.get();
            if (isVisible(trackers, locations, id.toString())) {
                return id;
            }
            AdvancementHolder next = manager.get(id);
            if (next == null) {
                return null;
            }
            if (next.value().parent().isEmpty()) {
                return id;  // the tab root: shown whenever a check sits below it (AdvancementVisibilityEvaluatorMixin)
            }
            parent = next.value().parent();
        }
        return null;
    }

    /** Mirrors what {@code AdvancementVisibilityEvaluatorMixin} shows of the tracker tabs. */
    private static boolean isVisible(APTrackerRegistry trackers, APLocationRegistry locations, String id) {
        return locations.isActiveLocation(id)
                || trackers.isTracker(id)
                || trackers.isActiveCategory(id)
                || id.equals(APTrackerRegistry.TAB_ROOT_ID)
                || id.equals(APTrackerRegistry.GOAL_ADVANCEMENTS_ID)
                || id.equals(APTrackerRegistry.GOAL_BOSSES_ID);
    }

    /** The same advancement under a different parent; everything else (criteria, display) is kept. */
    private static AdvancementHolder reparent(AdvancementHolder holder, Identifier parent) {
        Advancement base = holder.value();
        return new AdvancementHolder(holder.id(), new Advancement(
                Optional.of(parent), base.display(), base.rewards(), base.criteria(),
                base.requirements(), base.sendsTelemetryEvent(), base.name()));
    }
}
