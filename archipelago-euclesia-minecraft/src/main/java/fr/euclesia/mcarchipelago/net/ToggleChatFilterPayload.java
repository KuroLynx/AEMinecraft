package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * "Flip the chat filter" — sent by the keybind or the options-screen checkbox, both the same
 * request. The server is the sole authority: it flips {@code ChatFilterPreference}, persists it,
 * and broadcasts the new state to everyone via {@link ChatFilterSyncPayload}, so there is one code
 * path regardless of trigger or singleplayer/dedicated.
 */
public record ToggleChatFilterPayload() implements CustomPacketPayload {
    public static final ToggleChatFilterPayload INSTANCE = new ToggleChatFilterPayload();

    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "toggle_chat_filter");
    public static final CustomPacketPayload.Type<ToggleChatFilterPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleChatFilterPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
