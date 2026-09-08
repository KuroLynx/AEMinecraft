package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The chat filter's current on/off state, pushed from the server — on every toggle and on join,
 * so every connected client's local mirror ({@code ChatFilterPreference}, used for the
 * options-screen label and the keybind's confirmation message) matches the one authoritative copy
 * the server actually filters with.
 */
public record ChatFilterSyncPayload(boolean enabled) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AEM.MOD_ID, "chat_filter_state");
    public static final CustomPacketPayload.Type<ChatFilterSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ChatFilterSyncPayload> CODEC =
            StreamCodec.of(ChatFilterSyncPayload::write, ChatFilterSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, ChatFilterSyncPayload payload) {
        buf.writeBoolean(payload.enabled());
    }

    private static ChatFilterSyncPayload read(RegistryFriendlyByteBuf buf) {
        return new ChatFilterSyncPayload(buf.readBoolean());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
