package fr.euclesia.mcarchipelago.server.event;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.SayPacket;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnection;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnector;
import fr.euclesia.mcarchipelago.server.gameplay.AdvancementBridge;
import fr.euclesia.mcarchipelago.server.gameplay.MobKillBridge;
import fr.euclesia.mcarchipelago.server.gameplay.RootAdvancementService;
import fr.euclesia.mcarchipelago.server.gameplay.StartDimensionService;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.service.DeathLinkService;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

public final class MinecraftEventBridge {
    private static final long CONNECT_TIMEOUT_MS = 20_000L;

    private MinecraftEventBridge() {}

    public static void register() {
        // Connect-on-join gate. A world created via the Archipelago tab stages its connection here;
        // persist it into the new world's folder, then require a live Archipelago session before the
        // world finishes loading. If the connection fails, abort the load — you cannot enter a world
        // without being connected.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            java.nio.file.Path worldDir = server.getWorldPath(LevelResource.ROOT);

            APWorldConnection pending = APWorldConnection.takePending();
            if (pending != null) {
                pending.write(worldDir);
            }

            APWorldConnection connection = APWorldConnection.read(worldDir);
            if (connection == null || !connection.hasSlot()) {
                return;
            }
            if (AEM.ARCHIPELAGO.client().state().isConnected()) {
                return;
            }
            if (!APWorldConnector.connectBlocking(connection, CONNECT_TIMEOUT_MS)) {
                throw new IllegalStateException("Archipelago connection failed for " + connection.address
                        + ":" + connection.port + " (slot " + connection.slot + "); cannot enter the world.");
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(AEMServerRuntime::setServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AEMServerRuntime.clearServer(server);
            AEM.ARCHIPELAGO.client().close();
        });

        ServerPlayerEvents.JOIN.register(player -> {
            // Covers the connect-before-join path (e.g. main-menu connect): if the slot data is
            // already known, place the player in their start dimension before anything else.
            StartDimensionService.applyIfNeeded(player);
            // Rebuild + resync the tab root for this player before scanning, so the connect-before-join
            // path still gets the goal-count root (the connect-time reload ran with no players online).
            RootAdvancementService.applyOnJoin(player);
            AdvancementBridge.scanPlayer(player);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                DeathLinkService.onLocalPlayerDeath(player);
            } else {
                // Fires for every mob death; the bridge filters to player-credited kills.
                MobKillBridge.onMobKilled(entity, damageSource);
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
