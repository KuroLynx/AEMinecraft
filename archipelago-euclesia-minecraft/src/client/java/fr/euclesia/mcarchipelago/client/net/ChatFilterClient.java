package fr.euclesia.mcarchipelago.client.net;

import fr.euclesia.mcarchipelago.net.ToggleChatFilterPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Sends a chat-filter toggle request — used by the {@code key.aem.chat_filter} keybind and the
 * options-screen checkbox alike. The server is the sole authority; the result comes back via
 * {@code ChatFilterSyncPayload}, handled in {@link APStateSyncClient}. Send-only: no receiver to
 * register here.
 */
public final class ChatFilterClient {
    private ChatFilterClient() {}

    public static void requestToggle() {
        if (ClientPlayNetworking.canSend(ToggleChatFilterPayload.TYPE)) {
            ClientPlayNetworking.send(ToggleChatFilterPayload.INSTANCE);
        }
    }
}
