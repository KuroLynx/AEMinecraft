package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class AdvancementBridge {
    private AdvancementBridge() {}

    public static void onCompleted(ServerPlayer player, String advancementId) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return;
        }

        if (AEM.ARCHIPELAGO.gateway().checkLocation(advancementId)) {
            player.sendSystemMessage(Component.literal("Archipelago check: " + advancementId));
        }
    }

    public static void scanOnlinePlayers() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }

        server.execute(() -> server.getPlayerList().getPlayers().forEach(AdvancementBridge::scanPlayer));
    }

    public static void scanPlayer(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }

        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
            if (player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                onCompleted(player, advancement.id().toString());
            }
        }
    }
}
