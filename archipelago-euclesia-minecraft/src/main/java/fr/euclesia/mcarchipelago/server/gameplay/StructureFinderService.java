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
 * <p>Each copy of the finder reveals more of the surrounding structures: the nearest 5, then 10,
 * then half, then three-quarters, then every findable structure (see {@link #cap}). Only structures
 * that may currently generate are considered — a structure gated by the structure-lock option is
 * skipped until its unlock item arrives ({@link APStructureRegistry#isLocked}) — and only in the
 * player's current dimension (a structure with no placement here returns {@code null}, so the scope
 * is automatic and those checks are cheap).
 */
public final class StructureFinderService {
    /** The item whose received count is the finder tier. */
    public static final String FINDER_ITEM = "Progressive Structure Finder";
    /** Highest meaningful tier (5 copies = every findable structure shown). */
    public static final int MAX_TIER = 5;
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
     * How many of the {@code total} findable structures a given {@code tier} reveals: 5, 10, half,
     * three-quarters, then all. Computed as a running maximum so the count never drops as the tier
     * rises (e.g. with few structures, tier 2's "10" can already exceed tier 3's "half").
     */
    public static int cap(int tier, int total) {
        if (tier <= 0 || total <= 0) {
            return 0;
        }
        int[] perTier = {
                Math.min(5, total),
                Math.min(10, total),
                Math.ceilDiv(total, 2),       // half
                Math.ceilDiv(total * 3, 4),   // three quarters
                total,
        };
        int n = 0;
        for (int i = 0; i < Math.min(tier, perTier.length); i++) {
            n = Math.max(n, perTier[i]);
        }
        return Math.min(n, total);
    }

    /**
     * Every findable structure (nearest instance per unlocked type) in the player's dimension, sorted
     * nearest first. Uncapped — the caller applies {@link #cap} for the player's tier. Empty when
     * Archipelago is not ready or nothing is found.
     */
    public static List<FinderTarget> findAll(ServerPlayer player) {
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
            if (id == null || structures.isLocked(id.toString())) {
                continue;  // unknown key, or still gated by the structure-lock option
            }
            HolderSet<Structure> single = HolderSet.direct(ref);
            Pair<BlockPos, Holder<Structure>> nearest =
                    generator.findNearestMapStructure(level, single, origin, SEARCH_RADIUS, false);
            if (nearest == null) {
                continue;  // no placement in this dimension, or none within the radius
            }
            BlockPos pos = nearest.getFirst();
            results.add(new FinderTarget(id.toString(), pos, origin.distSqr(pos)));
        }
        results.sort(Comparator.comparingDouble(FinderTarget::distanceSq));
        return results;
    }
}
