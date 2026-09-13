package fr.euclesia.mcarchipelago.server.gameplay;

import com.mojang.serialization.Codec;
import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-dimension record of the structures whose placement was suppressed because they were locked:
 * structure id -> the origin chunk of each suppressed instance.
 *
 * <p>That is the whole record. {@link StructureReplayService} rebuilds a structure from it by asking
 * vanilla to generate the same start again — {@code Structure.generate} is a pure function of the
 * world seed, the origin chunk and the noise settings, so the start it returns is the one worldgen
 * made, piece for piece. Nothing about the structure's blocks needs storing.
 *
 * <p>This replaces {@link StructureCaptureData}, which stored every block of every locked structure
 * and re-encoded the lot through a record codec on every autosave. With {@code structure_unlock: All}
 * that is millions of blocks resident in heap, and it killed a server outright:
 * {@code OutOfMemoryError} inside {@code SavedDataStorage.collectDirtyTagsToSave}. The old data is
 * still read and drained for worlds that already have it; nothing new is ever written to it.
 */
public final class SuppressedStructureData extends SavedData {

    private static final Codec<Map<String, List<ChunkPos>>> MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, ChunkPos.CODEC.listOf());

    public static final Codec<SuppressedStructureData> CODEC =
            MAP_CODEC.xmap(SuppressedStructureData::fromMap, SuppressedStructureData::toMap);

    public static final SavedDataType<SuppressedStructureData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(AEM.MOD_ID, "suppressed_structures"),
            SuppressedStructureData::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    // structureId -> packed origin ChunkPos of each instance awaiting its unlock item. A set: one
    // structure start is placed chunk by chunk, so the same origin is offered once per chunk it spans.
    private final Map<String, Set<ChunkPos>> origins = new HashMap<>();

    public SuppressedStructureData() {}

    private static SuppressedStructureData fromMap(Map<String, List<ChunkPos>> map) {
        SuppressedStructureData data = new SuppressedStructureData();
        map.forEach((structureId, chunks) -> data.origins.put(structureId, new LinkedHashSet<>(chunks)));
        return data;
    }

    private Map<String, List<ChunkPos>> toMap() {
        Map<String, List<ChunkPos>> map = new HashMap<>();
        origins.forEach((structureId, chunks) -> {
            if (!chunks.isEmpty()) {
                map.put(structureId, new ArrayList<>(chunks));
            }
        });
        return map;
    }

    /** Records one suppressed instance. Idempotent per origin, so the per-chunk calls collapse. */
    public synchronized void add(String structureId, ChunkPos origin) {
        if (origins.computeIfAbsent(structureId, key -> new LinkedHashSet<>()).add(origin)) {
            setDirty();
        }
    }

    /** Every structure id with instances still awaiting their unlock. */
    public synchronized Set<String> suppressedIds() {
        return Set.copyOf(origins.keySet());
    }

    /** Takes every suppressed origin of one structure, leaving none behind. */
    public synchronized List<ChunkPos> drain(String structureId) {
        Set<ChunkPos> chunks = origins.remove(structureId);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        setDirty();
        return new ArrayList<>(chunks);
    }
}
