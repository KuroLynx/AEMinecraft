package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A pick from the Biome Finder screen. Three shapes:
 * <ul>
 *   <li>a biome id, no spot — locate the nearest one and point my compass at it (a legend click);</li>
 *   <li>a spot ({@code atSpot}) — point at exactly that x/z (a map click; the server reads the biome
 *       there itself, the id is ignored);</li>
 *   <li>an empty id, no spot — stop tracking (the screen's ✕).</li>
 * </ul>
 *
 * <p>The search itself is worldgen work and stays on the server.
 */
public record BiomeSearchPayload(String biomeId, boolean atSpot, int x, int z) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "biome_search");
    public static final CustomPacketPayload.Type<BiomeSearchPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, BiomeSearchPayload> CODEC =
            StreamCodec.of(BiomeSearchPayload::write, BiomeSearchPayload::read);

    public static BiomeSearchPayload nearest(String biomeId) {
        return new BiomeSearchPayload(biomeId, false, 0, 0);
    }

    public static BiomeSearchPayload spot(int x, int z) {
        return new BiomeSearchPayload("", true, x, z);
    }

    public static BiomeSearchPayload clear() {
        return nearest("");
    }

    private static void write(RegistryFriendlyByteBuf buf, BiomeSearchPayload payload) {
        buf.writeUtf(payload.biomeId());
        buf.writeBoolean(payload.atSpot());
        if (payload.atSpot()) {
            buf.writeInt(payload.x());
            buf.writeInt(payload.z());
        }
    }

    private static BiomeSearchPayload read(RegistryFriendlyByteBuf buf) {
        String biomeId = buf.readUtf();
        if (!buf.readBoolean()) {
            return nearest(biomeId);
        }
        return new BiomeSearchPayload(biomeId, true, buf.readInt(), buf.readInt());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
