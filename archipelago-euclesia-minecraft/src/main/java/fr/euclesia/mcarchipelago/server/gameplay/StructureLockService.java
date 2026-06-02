package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Decides whether a structure is currently forbidden by the Archipelago structure-lock option.
 * Queried from {@code StructureStartMixin} at {@code StructureStart#placeInChunk}: a locked structure
 * still computes its placement (so terrain/heightmap context is correct), but its writes are captured
 * instead of applied (see {@link StructureCapture} / {@link StructureCaptureService}) until the unlock
 * item arrives.
 */
public final class StructureLockService {
    private StructureLockService() {}

    /**
     * Returns the structure's game id when its placement should be captured rather than applied, or
     * {@code null} when it may generate normally.
     */
    public static String lockedStructureId(WorldGenLevel level, Structure structure) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return null;
        }
        Identifier id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
        if (id == null) {
            return null;
        }
        String structureId = id.toString();
        return AEM.ARCHIPELAGO.client().registries().apStructures().isLocked(structureId) ? structureId : null;
    }
}
