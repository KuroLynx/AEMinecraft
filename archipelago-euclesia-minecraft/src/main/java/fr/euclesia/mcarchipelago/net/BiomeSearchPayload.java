package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A pick from the Biome Finder screen: locate this biome and point my needle at it.
 *
 * <p>The search itself is worldgen work and stays on the server, which is also the only side that can
 * write the lodestone component onto the player's finder stack.
 */
public record BiomeSearchPayload(String biomeId) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "biome_search");
    public static final CustomPacketPayload.Type<BiomeSearchPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, BiomeSearchPayload> CODEC =
            StreamCodec.of(BiomeSearchPayload::write, BiomeSearchPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, BiomeSearchPayload payload) {
        buf.writeUtf(payload.biomeId());
    }

    private static BiomeSearchPayload read(RegistryFriendlyByteBuf buf) {
        return new BiomeSearchPayload(buf.readUtf());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
