package fr.euclesia.mcarchipelago.server.event;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.SayPacket;
import fr.euclesia.mcarchipelago.server.gameplay.AdvancementBridge;
import fr.euclesia.mcarchipelago.server.gameplay.MobKillBridge;
import fr.euclesia.mcarchipelago.server.gameplay.StartDimensionService;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.service.DeathLinkService;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerPlayer;

public final class MinecraftEventBridge {
    private MinecraftEventBridge() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(AEMServerRuntime::setServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AEMServerRuntime.clearServer(server);
            AEM.ARCHIPELAGO.client().close();
        });

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, damageSource) ->
                MobKillBridge.onMobKilled(killed));

        ServerPlayerEvents.JOIN.register(player -> {
            // Covers the connect-before-join path (e.g. main-menu connect): if the slot data is
            // already known, place the player in their start dimension before anything else.
            StartDimensionService.applyIfNeeded(player);
            AdvancementBridge.scanPlayer(player);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                DeathLinkService.onLocalPlayerDeath(player);
            }
        });

        // Forward in-game chat to Archipelago (Say) so it reaches every text client. When
        // connected we suppress the vanilla local broadcast and let the server's PrintJSON echo
        // render it back, so chat shows once and consistently through Archipelago.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!AEMServerRuntime.isArchipelagoReady()) {
                return true;
            }
            AEM.ARCHIPELAGO.client().send(new SayPacket(message.signedContent()));
            return false;
        });
    }
}
