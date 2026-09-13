package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A hold-to-hint request from an advancement tile: "this is the tile I was held on, hint its
 * item." The server resolves and validates the id itself rather than trusting a name from the
 * client — see {@link HintNet}.
 */
public record RequestItemHintPayload(String advancementId) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "request_item_hint");
    public static final CustomPacketPayload.Type<RequestItemHintPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestItemHintPayload> CODEC =
            StreamCodec.of(RequestItemHintPayload::write, RequestItemHintPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, RequestItemHintPayload payload) {
        buf.writeUtf(payload.advancementId());
    }

    private static RequestItemHintPayload read(RegistryFriendlyByteBuf buf) {
        return new RequestItemHintPayload(buf.readUtf());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
