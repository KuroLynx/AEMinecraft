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

    /**
     * The RUN's finder tier, 0 (none) to {@link #MAX_TIER}, from the Finder items this slot has
     * received.
     *
     * <p>Deliberately takes no player. A server is many people playing one Archipelago slot, so the
     * Finder belongs to the run and everybody sees the same tier — there is no per-player count to
     * read. It used to take a {@code ServerPlayer} and ignore it, which read as though the tier were
     * personal and is exactly the kind of signature that invites a wrong assumption.
     */
    public static int tier() {
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
     * The structure types that are candidates for the finder in {@code level}: every registered
     * structure that is not still gated by the structure-lock option. Position-independent, so the
     * caller can compute this once and spread the (heavy) per-type nearest searches via {@link
     * #nearest}. Takes a {@link ServerLevel} (not a player) so it can run at server start, before any
     * player exists. Empty when Archipelago is not ready.
     */
    public static List<Holder.Reference<Structure>> candidates(ServerLevel level) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return List.of();
        }
        APStructureRegistry structures = AEM.ARCHIPELAGO.client().registries().apStructures();
        Registry<Structure> registry =
                level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder.Reference<Structure>> result = new ArrayList<>();
        for (Holder.Reference<Structure> ref : registry.listElements().toList()) {
            Identifier id = registry.getKey(ref.value());
            if (id == null || structures.isLocked(id.toString())) {
                continue;  // unknown key, or still gated by the structure-lock option
            }
            result.add(ref);
        }
        return result;
    }

    /**
     * The nearest instance of a single structure {@code ref} from {@code origin} in {@code level}, or
     * {@code null} if it has no placement there or none within the search radius. This is the heavy
     * part (one {@code /locate}-style worldgen search) and must run on the server thread; callers
     * either run it all at once behind the loading screen or budget how many they run per tick.
     */
    public static FinderTarget nearest(ServerLevel level, BlockPos origin,
                                       Holder.Reference<Structure> ref) {
        Identifier id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(ref.value());
        if (id == null) {
            return null;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        HolderSet<Structure> single = HolderSet.direct(ref);
        Pair<BlockPos, Holder<Structure>> found =
                generator.findNearestMapStructure(level, single, origin, SEARCH_RADIUS, false);
        if (found == null) {
            return null;  // no placement in this dimension, or none within the radius
        }
        BlockPos pos = found.getFirst();
        return new FinderTarget(id.toString(), pos, origin.distSqr(pos));
    }
}
