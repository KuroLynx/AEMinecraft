package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.APStructureRegistry;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Decides whether a structure (or a structure-like placed feature, e.g. {@code minecraft:desert_well})
 * is currently forbidden by the Archipelago structure-lock option. A locked thing still computes its
 * placement (so terrain/heightmap context is correct), but its writes are captured instead of applied
 * (see {@link StructureCapture} / {@link StructureCaptureService}) until the unlock item arrives.
 *
 * <p>Some entries in the {@code structure_locks} list are real {@code StructureStart} structures
 * (handled at {@code StructureStartMixin}); others — like the Desert Well — are vanilla
 * {@link PlacedFeature}s placed during biome decoration (handled at {@code PlacedFeatureMixin}).
 */
public final class StructureLockService {
    private StructureLockService() {}

    /**
     * Returns the structure's game id when its placement should be captured rather than applied, or
     * {@code null} when it may generate normally.
     */
    public static String lockedStructureId(WorldGenLevel level, Structure structure) {
        APStructureRegistry registry = lockRegistry();
        if (registry == null) {
            return null;
        }
        Identifier id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
        return id != null && registry.isLocked(id.toString()) ? id.toString() : null;
    }

    /**
     * Returns the placed feature's game id when its placement should be captured rather than applied,
     * or {@code null} when it may generate normally. Lets feature-based "structures" (Desert Well,
     * fossils, ...) be locked the same way as real structures.
     */
    public static String lockedFeatureId(WorldGenLevel level, PlacedFeature feature) {
        APStructureRegistry registry = lockRegistry();
        if (registry == null) {
            return null;
        }
        Identifier id = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getKey(feature);
        return id != null && registry.isLocked(id.toString()) ? id.toString() : null;
    }

    /**
     * Whether a structure is currently locked, resolved against a live {@link RegistryAccess} (the
     * worldgen-time {@link #lockedStructureId} variant needs a {@code WorldGenLevel}). Used to hide a
     * locked structure from "inside structure" queries on the live world ({@code StructureManagerMixin})
     * so advancements like Trial Chambers / BACAP structure goals don't fire off a captured (unplaced)
     * structure. Reflects the live unlock state, so it flips to {@code false} once the unlock arrives.
     */
    public static boolean isStructureLocked(RegistryAccess registryAccess, Structure structure) {
        APStructureRegistry registry = lockRegistry();
        if (registry == null) {
            return false;
        }
        Identifier id = registryAccess.lookupOrThrow(Registries.STRUCTURE).getKey(structure);
        return id != null && registry.isLocked(id.toString());
    }

    /** The lock registry only when Archipelago is connected and something is actually locked. */
    private static APStructureRegistry lockRegistry() {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return null;
        }
        APStructureRegistry registry = AEM.ARCHIPELAGO.client().registries().apStructures();
        return registry.hasLocks() ? registry : null;
    }
}
