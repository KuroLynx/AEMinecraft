package fr.euclesia.mcarchipelago.server.gameplay;

import com.mojang.datafixers.util.Pair;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.APItemRegistry;
import fr.euclesia.mcarchipelago.registry.APStructureRegistry;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Locates the nearest <em>unlocked</em> structures for the Progressive Structure Finder. Uses the
 * same worldgen search as the vanilla {@code /locate structure} command
 * ({@link ChunkGenerator#findNearestMapStructure}), so it finds structures that have never been
 * visited and needs no dependency on the structure-capture data (which is consumed on unlock).
 *
 * <p>Only structures that may currently generate are considered: a structure gated by the
 * structure-lock option is skipped until its unlock item arrives ({@link APStructureRegistry#isLocked}),
 * while a structure that was never locked is always eligible. The search runs against the player's
 * current dimension only — a structure with no placement in this dimension's generator returns
 * {@code null}, so the per-dimension scope is automatic (and those checks are cheap).
 */
public final class StructureFinderService {
    /** The item whose received count is the finder tier (1 = bar, 2 = + compass, 3 = + selection). */
    public static final String FINDER_ITEM = "Progressive Structure Finder";
    /** Cap on the passive locator-bar list: the N nearest distinct structure types. */
    public static final int MAX_TARGETS = 5;
    /** Highest meaningful tier (3 copies of the item). */
    public static final int MAX_TIER = 3;
    /** Chunk radius of the worldgen search (matches /locate structure's default). */
    private static final int SEARCH_RADIUS = 100;

    private StructureFinderService() {}

    /** The player's finder tier, 0 (none) to {@link #MAX_TIER}, from received Finder items. */
    public static int tier(ServerPlayer player) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return 0;
        }
        APItemRegistry items = AEM.ARCHIPELAGO.client().registries().apItems();
        return Math.min(items.receivedCount(FINDER_ITEM), MAX_TIER);
    }

    /**
     * The nearest instance of each unlocked structure type in the player's dimension, sorted nearest
     * first and capped at {@link #MAX_TARGETS}. Empty when Archipelago is not ready or nothing is found.
     */
    public static List<FinderTarget> nearestPerType(ServerPlayer player) {
        List<FinderTarget> found = searchUnlocked(player, null);
        found.sort(Comparator.comparingDouble(FinderTarget::distanceSq));
        if (found.size() > MAX_TARGETS) {
            return new ArrayList<>(found.subList(0, MAX_TARGETS));
        }
        return found;
    }

    /** The nearest instance of one specific structure type (tier-3 selection), or {@code null}. */
    public static FinderTarget nearestOfType(ServerPlayer player, String structureId) {
        List<FinderTarget> found = searchUnlocked(player, structureId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Finds the nearest instance of every eligible structure (or only {@code onlyStructureId} when
     * non-null). One result per structure type, unsorted.
     */
    private static List<FinderTarget> searchUnlocked(ServerPlayer player, String onlyStructureId) {
        List<FinderTarget> results = new ArrayList<>();
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return results;
        }
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        APStructureRegistry structures = AEM.ARCHIPELAGO.client().registries().apStructures();
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);

        for (Holder.Reference<Structure> ref : registry.listElements().toList()) {
            Identifier id = registry.getKey(ref.value());
            if (id == null) {
                continue;
            }
            String gameId = id.toString();
            if (onlyStructureId != null && !onlyStructureId.equals(gameId)) {
                continue;
            }
            if (structures.isLocked(gameId)) {
                continue;  // still gated by the structure-lock option
            }
            HolderSet<Structure> single = HolderSet.direct(ref);
            Pair<BlockPos, Holder<Structure>> nearest =
                    generator.findNearestMapStructure(level, single, origin, SEARCH_RADIUS, false);
            if (nearest == null) {
                continue;  // no placement in this dimension, or none within the radius
            }
            BlockPos pos = nearest.getFirst();
            results.add(new FinderTarget(gameId, pos, origin.distSqr(pos)));
        }
        return results;
    }
}
