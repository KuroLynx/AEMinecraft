package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureData.CapturedBlock;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureData.CapturedPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-local diversion of a locked structure's placement. While a session is active on the worldgen
 * thread (set up by {@code StructureStartMixin} around {@code placeInChunk}), {@code WorldGenRegionMixin}
 * routes every block, block-entity and entity write into the session instead of the world. The session
 * is then serialized and persisted (see {@link StructureCaptureService}) so it can be applied verbatim
 * once the structure unlocks — preserving the worldgen-time placement exactly.
 */
public final class StructureCapture {
    private static final ThreadLocal<CaptureSession> CURRENT = new ThreadLocal<>();

    private StructureCapture() {}

    public static CaptureSession current() {
        return CURRENT.get();
    }

    public static void begin(String structureId) {
        // Defensive: clear any session left behind by a placement that threw before its RETURN hook.
        CURRENT.set(new CaptureSession(structureId));
    }

    public static CaptureSession end() {
        CaptureSession session = CURRENT.get();
        CURRENT.remove();
        return session;
    }

    /** Accumulates the diverted writes of a single {@code placeInChunk} call. */
    public static final class CaptureSession {
        private final String structureId;
        // Insertion order preserved so the structure re-applies in the order it was placed.
        private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        private final List<Entity> entities = new ArrayList<>();

        CaptureSession(String structureId) {
            this.structureId = structureId;
        }

        public String structureId() {
            return structureId;
        }

        public boolean isEmpty() {
            return blocks.isEmpty() && entities.isEmpty();
        }

        public void captureBlock(BlockPos pos, BlockState state) {
            blocks.put(pos, state);
            // Mirror WorldGenRegion#setBlock: create the block entity so the piece can populate it
            // (loot tables, spawner data, signs, ...) and we can serialize it afterwards.
            if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock entityBlock) {
                BlockEntity blockEntity = entityBlock.newBlockEntity(pos, state);
                if (blockEntity != null) {
                    blockEntities.put(pos, blockEntity);
                    return;
                }
            }
            blockEntities.remove(pos);
        }

        public BlockState blockAt(BlockPos pos) {
            return blocks.get(pos);
        }

        public BlockEntity blockEntityAt(BlockPos pos) {
            return blockEntities.get(pos);
        }

        public void captureEntity(Entity entity) {
            // addFreshEntityWithPassengers adds the root and each passenger separately; only keep roots,
            // since a root's NBT already serializes its passenger stack (avoids double-spawning them).
            if (!entity.isPassenger()) {
                entities.add(entity);
            }
        }

        /** Serializes the captured writes into the persistable form. Call on the capturing thread. */
        public CapturedPlacement toPersistable(HolderLookup.Provider provider) {
            List<CapturedBlock> capturedBlocks = new ArrayList<>(blocks.size());
            blocks.forEach((pos, state) -> {
                BlockEntity blockEntity = blockEntities.get(pos);
                Optional<CompoundTag> blockEntityTag = blockEntity == null
                        ? Optional.empty()
                        : Optional.of(blockEntity.saveWithFullMetadata(provider));
                capturedBlocks.add(new CapturedBlock(pos, state, blockEntityTag));
            });

            List<CompoundTag> capturedEntities = new ArrayList<>(entities.size());
            for (Entity entity : entities) {
                TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, provider);
                if (entity.save(output)) {
                    capturedEntities.add(output.buildResult());
                }
            }
            return new CapturedPlacement(capturedBlocks, capturedEntities);
        }
    }
}
