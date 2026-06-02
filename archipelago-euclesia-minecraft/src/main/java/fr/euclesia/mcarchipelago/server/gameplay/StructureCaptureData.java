package fr.euclesia.mcarchipelago.server.gameplay;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-dimension persistent record of the writes a locked structure's {@code placeInChunk} produced
 * (captured by {@link StructureCapture} instead of applied to the world). Persisted so a structure
 * whose chunks generated while it was locked still appears, byte-for-byte, when the unlock item arrives
 * in this or a later session. {@link StructureCaptureService} drains and applies it.
 */
public final class StructureCaptureData extends SavedData {

    /** A single captured block: its position, state, and the block entity's NBT if it had one. */
    public record CapturedBlock(BlockPos pos, BlockState state, Optional<CompoundTag> blockEntity) {
        public static final Codec<CapturedBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(CapturedBlock::pos),
                BlockState.CODEC.fieldOf("state").forGetter(CapturedBlock::state),
                CompoundTag.CODEC.optionalFieldOf("be").forGetter(CapturedBlock::blockEntity)
        ).apply(instance, CapturedBlock::new));
    }

    /** All writes captured for one {@code placeInChunk} call. */
    public record CapturedPlacement(List<CapturedBlock> blocks, List<CompoundTag> entities) {
        public static final Codec<CapturedPlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                CapturedBlock.CODEC.listOf().fieldOf("blocks").forGetter(CapturedPlacement::blocks),
                CompoundTag.CODEC.listOf().fieldOf("entities").forGetter(CapturedPlacement::entities)
        ).apply(instance, CapturedPlacement::new));
    }

    private static final Codec<Map<String, List<CapturedPlacement>>> MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, CapturedPlacement.CODEC.listOf());

    public static final Codec<StructureCaptureData> CODEC =
            MAP_CODEC.xmap(StructureCaptureData::fromMap, StructureCaptureData::toMap);

    public static final SavedDataType<StructureCaptureData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(AEM.MOD_ID, "captured_structures"),
            StructureCaptureData::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    // structureId -> captured placements awaiting the unlock item.
    private final Map<String, List<CapturedPlacement>> captured = new HashMap<>();

    public StructureCaptureData() {}

    private static StructureCaptureData fromMap(Map<String, List<CapturedPlacement>> map) {
        StructureCaptureData data = new StructureCaptureData();
        map.forEach((structureId, placements) -> data.captured.put(structureId, new ArrayList<>(placements)));
        return data;
    }

    private Map<String, List<CapturedPlacement>> toMap() {
        Map<String, List<CapturedPlacement>> map = new HashMap<>();
        captured.forEach((structureId, placements) -> {
            if (!placements.isEmpty()) {
                map.put(structureId, new ArrayList<>(placements));
            }
        });
        return map;
    }

    public synchronized void add(String structureId, CapturedPlacement placement) {
        captured.computeIfAbsent(structureId, key -> new ArrayList<>()).add(placement);
        setDirty();
    }

    public synchronized List<CapturedPlacement> drain(String structureId) {
        List<CapturedPlacement> removed = captured.remove(structureId);
        if (removed != null && !removed.isEmpty()) {
            setDirty();
            return removed;
        }
        return List.of();
    }
}
