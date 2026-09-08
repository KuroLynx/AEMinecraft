package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.archipelago.ChatFilterPreference;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.service.ChatFilterSetting;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;

/**
 * Server side of the chat filter toggle: the keybind and the options-screen checkbox both send
 * {@link ToggleChatFilterPayload}; this flips {@link ChatFilterPreference}, persists it for the
 * world, and broadcasts the new state to every online player via {@link ChatFilterSyncPayload} —
 * it is a run-wide setting, so everyone's local mirror should move together.
 */
public final class ChatFilterNet {
    private ChatFilterNet() {}

    /** Registers the serverbound payload type and its handler. Called from mod init, both sides. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay()
                .register(ToggleChatFilterPayload.TYPE, ToggleChatFilterPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ToggleChatFilterPayload.TYPE,
                (payload, context) -> toggle());
    }

    private static void toggle() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        boolean enabled = !ChatFilterPreference.enabled();
        ChatFilterPreference.setEnabled(enabled);
        ChatFilterSetting.save(server, enabled);
        broadcast(server, enabled);
    }

    private static void broadcast(MinecraftServer server, boolean enabled) {
        server.getPlayerList().getPlayers().forEach(player -> APStateSync.sendChatFilter(player, enabled));
    }
}
