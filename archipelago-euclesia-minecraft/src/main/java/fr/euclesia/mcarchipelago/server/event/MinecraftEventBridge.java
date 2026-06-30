package fr.euclesia.mcarchipelago.server.event;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.SayPacket;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnection;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnector;
import fr.euclesia.mcarchipelago.server.gameplay.AdvancementBridge;
import fr.euclesia.mcarchipelago.server.gameplay.BacapConfigService;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderService;
import fr.euclesia.mcarchipelago.server.gameplay.FillerTrapService;
import fr.euclesia.mcarchipelago.server.gameplay.MobKillBridge;
import fr.euclesia.mcarchipelago.server.gameplay.RootAdvancementService;
import fr.euclesia.mcarchipelago.server.gameplay.StartDimensionService;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderDriver;
import fr.euclesia.mcarchipelago.server.gameplay.TrapMobService;
import fr.euclesia.mcarchipelago.server.gameplay.TrapPlatformService;
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
        // Recompute the Structure Finder's targets on the server tick (throttled) and publish them
        // to StructureFinderState for the client to render.
        StructureFinderDriver.register();

        // Connect-on-join gate. A world created via the Archipelago tab stages its connection here;
        // persist it into the new world's folder, then require a live Archipelago session before the
        // world finishes loading. In normal singleplayer the client pre-flight (WorldOpenFlowsMixin)
        // has already connected before this runs, so the isConnected() check below returns early; this
        // remains the safety net for any path that reaches server start without a live session (e.g.
        // a dedicated server), where there is no client UI to fall back to.
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
            // Apply the one-time BACAP reward/trophy config (no-op if already done or BACAP is off).
            BacapConfigService.applyIfNeeded(AEMServerRuntime.server());
            // Rebuild + resync the tab root for this player before scanning, so the connect-before-join
            // path still gets the goal-count root (the connect-time reload ran with no players online).
            RootAdvancementService.applyOnJoin(player);
            AdvancementBridge.scanPlayer(player);
            // Give back the soulbound Biome Finder if this slot owns it (covers first join and relog).
            BiomeFinderService.ensureGranted(player);
            // Apply any filler/trap effects received while offline (and before this join).
            FillerTrapService.applyPending(player);
        });

        // The Biome Finder is soulbound: restore the exact stack saved at death (keeping its tracked
        // biome), or grant a fresh one if none was saved.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                BiomeFinderService.restoreOnRespawn(newPlayer));

        // Before death drops are computed, save the finder (with its tracking) and strip it from the
        // inventory so it isn't dropped; AFTER_RESPAWN restores it. Always allow the death itself.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            if (entity instanceof ServerPlayer player) {
                BiomeFinderService.onDeath(player);
            }
            return true;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                DeathLinkService.onLocalPlayerDeath(player, damageSource);
                // A death clears any trap mobs/MLG platform aimed at the player.
                TrapMobService.discardAll();
                TrapPlatformService.onPlayerDeath(player);
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
