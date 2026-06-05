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

    public static void applyRemote(String source, String cause) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }

        Component message = Component.translatable("message.aem.deathlink", describe(source, cause));
        server.execute(() -> {
            suppressSend = true;
            try {
                server.getPlayerList().broadcastSystemMessage(message, false);
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

    /**
     * Builds the detail half of the DeathLink message. Archipelago's {@code cause} field is already a
     * full sentence (e.g. "Steve was slain by a Zombie") when present, so it is shown verbatim;
     * otherwise we fall back to naming the source slot, and only then to a generic phrase.
     */
    private static Component describe(String source, String cause) {
        if (cause != null && !cause.isBlank()) {
            return Component.literal(cause);
        }
        String name = source != null && !source.isBlank() ? source : null;
        return name != null
                ? Component.translatable("message.aem.deathlink.unknown", name)
                : Component.translatable("message.aem.deathlink.anonymous");
    }

    private static JsonObject createPayload(ServerPlayer player) {
        JsonObject data = new JsonObject();
        data.addProperty("time", Instant.now().toEpochMilli() / 1000.0);
        data.addProperty("source", player.getGameProfile().name());
        data.addProperty("cause", player.getCombatTracker().getDeathMessage().getString());
        return data;
    }
}