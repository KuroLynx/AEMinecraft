package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Rebuilds a locked structure when its unlock item arrives, from nothing but its id and origin chunk.
 *
 * <p>A locked structure's {@code placeInChunk} is skipped outright (see {@code StructureStartMixin}),
 * and only {@code (structure id, origin ChunkPos)} is recorded in {@link SuppressedStructureData}. On
 * unlock this asks vanilla to generate that structure's start again and places it chunk by chunk —
 * the same two steps, in the same order, that {@code /place structure} performs:
 *
 * <pre>
 *   Structure.generate(holder, dimension, registries, generator, biomeSource, randomState,
 *                      templateManager, level.getSeed(), origin, 0, level, biome -> true)
 *   start.getBoundingBox() -> ChunkPos.rangeClosed(min, max)
 *        -> start.placeInChunk(level, level.structureManager(), generator, level.getRandom(),
 *                              <that chunk's column>, chunkPos)
 * </pre>
 *
 * <p>The start comes back identical to the one worldgen made: {@code generate} reads no chunk data,
 * only the seed, the origin chunk and the noise settings, so the pieces, rotation and Y are the ones
 * the seed always dictated. And because only the block writes were ever suppressed — the start itself
 * was created and stored in the chunk as usual — the terrain was already adapted around it at noise
 * time, so the rebuilt structure lands in ground prepared for it.
 *
 * <p>What it cannot do is undo what happened meanwhile: a tree grown inside the footprint, or a
 * player's build, stays where it is and the structure is written around it. That is the same artifact
 * {@code /place structure} has always had, and the price of not storing several hundred megabytes of
 * block data (see {@link SuppressedStructureData}).
 */
public final class StructureReplayService {

    private StructureReplayService() {}

    /** Records that this structure's placement was skipped. Server thread (hopped by the caller). */
    public static void recordSuppressed(ServerLevel level, String structureId, ChunkPos origin) {
        suppressedData(level).add(structureId, origin);
    }

    /** Structure ids with instances awaiting their unlock in this level (see SlotReleaseService). */
    public static Set<String> suppressedStructureIds(ServerLevel level) {
        return suppressedData(level).suppressedIds();
    }

    /**
     * Queues every suppressed instance of the given structures for rebuilding. Server thread.
     *
     * <p>Queued, not rebuilt here: one unlock releases every instance of that type the world has
     * generated — with {@code structure_unlock: All} that can be every village anyone has walked
     * past — and each is dozens of chunks of writes. See {@link StructurePlacementQueue}.
     */
    public static void replayUnlocked(MinecraftServer server, Set<String> structureIds) {
        if (structureIds.isEmpty()) {
            return;
        }
        int queued = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SuppressedStructureData data = suppressedData(level);
            for (String structureId : structureIds) {
                for (ChunkPos origin : data.drain(structureId)) {
                    StructurePlacementQueue.enqueueRebuild(level, structureId, origin);
                    queued++;
                }
            }
        }
        if (queued > 0) {
            AEM.LOGGER.info("Unlocked {} structure type(s): {} instance(s) queued for rebuild.",
                    structureIds.size(), queued);
        }
    }

    /** Puts an instance back when the server stops before it could be rebuilt. */
    public static void restoreSuppressed(ServerLevel level, String structureId, ChunkPos origin) {
        suppressedData(level).add(structureId, origin);
    }

    /**
     * Regenerates one instance's start, or empty when the structure is unknown to this seed's
     * registries or the start no longer generates there (a datapack or version change since).
     */
    public static Optional<StructureStart> regenerate(ServerLevel level, String structureId, ChunkPos origin) {
        Identifier id = Identifier.tryParse(structureId);
        if (id == null) {
            return Optional.empty();
        }
        Optional<Holder.Reference<Structure>> holder = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .get(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            AEM.LOGGER.warn("Cannot rebuild {}: no such structure in this world's registries.", structureId);
            return Optional.empty();
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        StructureStart start = holder.get().value().generate(
                holder.get(),
                level.dimension(),
                level.registryAccess(),
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                level.getSeed(),
                origin,
                0,
                level,
                biome -> true);   // the biome already accepted it once, at worldgen
        return start.isValid() ? Optional.of(start) : Optional.empty();
    }

    /** Every chunk a start covers, in the order vanilla's own /place walks them. */
    public static List<ChunkPos> chunksOf(StructureStart start) {
        BoundingBox box = start.getBoundingBox();
        ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()),
                                    SectionPos.blockToSectionCoord(box.minZ()));
        ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()),
                                    SectionPos.blockToSectionCoord(box.maxZ()));
        List<ChunkPos> chunks = new ArrayList<>();
        ChunkPos.rangeClosed(min, max).forEach(chunks::add);
        return chunks;
    }

    /**
     * Places one chunk's worth of a start. The bounding box is that chunk's full column, exactly as
     * {@code /place structure} builds it, so a piece is written only where it belongs.
     */
    public static void placeChunk(ServerLevel level, StructureStart start, ChunkPos chunkPos) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        BoundingBox column = new BoundingBox(
                chunkPos.getMinBlockX(), level.getMinY(), chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX(), level.getMaxY(), chunkPos.getMaxBlockZ());
        start.placeInChunk(level, level.structureManager(), generator, level.getRandom(), column, chunkPos);
    }

    private static SuppressedStructureData suppressedData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SuppressedStructureData.TYPE);
    }
}
