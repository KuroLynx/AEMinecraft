package fr.euclesia.mcarchipelago.server.service;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.protocol.APBounceType;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;

public final class DeathLinkService {
    private static boolean suppressSend;

    private DeathLinkService() {}

    public static void onLocalPlayerDeath(ServerPlayer player) {
        if (!AEMServerRuntime.isArchipelagoReady() || suppressSend) {
            return;
        }

        if (AEM.ARCHIPELAGO.client().state().parsedSlotData().deathLink()) {
            AEM.ARCHIPELAGO.gateway().bounce(APBounceType.DEATH_LINK, createPayload(player));
        }
    }

    public static void applyRemote(String cause) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }

        server.execute(() -> {
            suppressSend = true;
            try {
                server.getPlayerList().broadcastSystemMessage(Component.literal("DeathLink: " + cause), false);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.isAlive()) {
                        player.kill(player.level());
                    }
                }
            } finally {
                suppressSend = false;
            }
        });
    }

    private static JsonObject createPayload(ServerPlayer player) {
        JsonObject data = new JsonObject();
        data.addProperty("time", Instant.now().toEpochMilli() / 1000.0);
        data.addProperty("source", player.getGameProfile().name());
        data.addProperty("cause", player.getCombatTracker().getDeathMessage().getString());
        return data;
    }
}