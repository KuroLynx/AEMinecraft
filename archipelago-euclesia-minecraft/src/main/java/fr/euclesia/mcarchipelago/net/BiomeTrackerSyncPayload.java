package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderTrackerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One player's tracked biome, pushed from the server — the Biome Finder equivalent of
 * {@link FinderSyncPayload}, needed for the same reason: {@link BiomeFinderTrackerState} is a
 * shared singleton that only lines up for free while client and server share a process, so a
 * dedicated server has to push it over the wire for the client's copy to have anything to read.
 *
 * <p>{@code present} false means "nothing tracked" (clears the client's copy) rather than a
 * separate payload, so there is exactly one message type for the whole lifecycle.
 */
public record BiomeTrackerSyncPayload(boolean present, String biomeId, String dimensionId, BlockPos pos)
        implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "biome_tracker_state");
    public static final CustomPacketPayload.Type<BiomeTrackerSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    private static final BiomeTrackerSyncPayload ABSENT =
            new BiomeTrackerSyncPayload(false, "", "", BlockPos.ZERO);

    public static final StreamCodec<RegistryFriendlyByteBuf, BiomeTrackerSyncPayload> CODEC =
            StreamCodec.of(BiomeTrackerSyncPayload::write, BiomeTrackerSyncPayload::read);

    public static BiomeTrackerSyncPayload absent() {
        return ABSENT;
    }

    public static BiomeTrackerSyncPayload of(BiomeFinderTrackerState.Target target) {
        return new BiomeTrackerSyncPayload(true, target.biomeId(), target.dimension().identifier().toString(),
                target.pos());
    }

    /** Reconstructs the tracked target, or {@code null} if this payload carries none (or is malformed). */
    public BiomeFinderTrackerState.Target toTarget() {
        if (!present) {
            return null;
        }
        Identifier dim = Identifier.tryParse(dimensionId);
        if (dim == null) {
            return null;
        }
        return new BiomeFinderTrackerState.Target(biomeId, ResourceKey.create(Registries.DIMENSION, dim), pos);
    }

    private static void write(RegistryFriendlyByteBuf buf, BiomeTrackerSyncPayload payload) {
        buf.writeBoolean(payload.present());
        if (payload.present()) {
            buf.writeUtf(payload.biomeId());
            buf.writeUtf(payload.dimensionId());
            buf.writeBlockPos(payload.pos());
        }
    }

    private static BiomeTrackerSyncPayload read(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return ABSENT;
        }
        String biomeId = buf.readUtf();
        String dimensionId = buf.readUtf();
        BlockPos pos = buf.readBlockPos();
        return new BiomeTrackerSyncPayload(true, biomeId, dimensionId, pos);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
