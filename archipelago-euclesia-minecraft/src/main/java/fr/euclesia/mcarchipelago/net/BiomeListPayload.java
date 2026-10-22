package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The biomes the sender's dimension can actually generate, answering {@link BiomeListRequestPayload},
 * plus a top-down biome map around the player for the pick screen.
 *
 * <p>The client cannot work this out for itself. Biome ids it has — the registry is synced on join —
 * but which of them a dimension can produce comes from that dimension's chunk generator, and the
 * client has no chunk generator. Reading it out of the integrated server worked only while the two
 * shared a process; on a dedicated server the pick screen came up empty.
 *
 * <p>The map is {@code size}×{@code size} cells, {@code step} blocks apart, centred on
 * ({@code centerX}, {@code centerZ}); row-major from the north-west corner. Each cell is an index into
 * {@code biomeIds} read as an unsigned byte, or 255 where the biome is not in the list. {@code size} 0 means no map.
 */
public record BiomeListPayload(List<String> biomeIds, int centerX, int centerZ, int step, int size, byte[] cells)
        implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "biome_list");
    public static final CustomPacketPayload.Type<BiomeListPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /** Vanilla has ~60 biomes and datapacks add more; this is a sanity bound, not a design limit. */
    private static final int MAX_BIOMES = 4096;
    private static final int MAX_SIZE = 512;

    public static final StreamCodec<RegistryFriendlyByteBuf, BiomeListPayload> CODEC =
            StreamCodec.of(BiomeListPayload::write, BiomeListPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, BiomeListPayload payload) {
        buf.writeVarInt(payload.biomeIds().size());
        for (String biomeId : payload.biomeIds()) {
            buf.writeUtf(biomeId);
        }
        buf.writeInt(payload.centerX());
        buf.writeInt(payload.centerZ());
        buf.writeVarInt(payload.step());
        buf.writeVarInt(payload.size());
        buf.writeByteArray(payload.cells());
    }

    private static BiomeListPayload read(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_BIOMES);
        List<String> biomeIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            biomeIds.add(buf.readUtf());
        }
        int centerX = buf.readInt();
        int centerZ = buf.readInt();
        int step = buf.readVarInt();
        int size = Math.min(buf.readVarInt(), MAX_SIZE);
        byte[] cells = buf.readByteArray(MAX_SIZE * MAX_SIZE);
        if (cells.length != size * size) {
            size = 0; // malformed: keep the list, drop the map
        }
        return new BiomeListPayload(List.copyOf(biomeIds), centerX, centerZ, step, size, cells);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
