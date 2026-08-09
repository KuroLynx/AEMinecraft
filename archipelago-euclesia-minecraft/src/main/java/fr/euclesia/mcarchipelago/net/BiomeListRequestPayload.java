package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * "What can I search for from here?" — sent when the Biome Finder pick screen opens.
 *
 * <p>Carries nothing: the answer depends only on which dimension the sender is standing in, and the
 * server already knows that. Asking rather than being pushed the list keeps it honest — the screen
 * cannot show the Nether's biomes to someone who walked back out of a portal, because every open
 * asks again.
 */
public record BiomeListRequestPayload() implements CustomPacketPayload {
    public static final BiomeListRequestPayload INSTANCE = new BiomeListRequestPayload();

    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "biome_list_request");
    public static final CustomPacketPayload.Type<BiomeListRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, BiomeListRequestPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
