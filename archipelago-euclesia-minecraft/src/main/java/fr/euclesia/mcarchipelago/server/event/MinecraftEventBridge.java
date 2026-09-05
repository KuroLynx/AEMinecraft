package fr.euclesia.mcarchipelago.server.event;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.SayPacket;
import fr.euclesia.mcarchipelago.net.APStateSync;
import fr.euclesia.mcarchipelago.server.connect.AEMServerConfig;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnection;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnector;
import fr.euclesia.mcarchipelago.server.gameplay.AdvancementBridge;
import fr.euclesia.mcarchipelago.server.gameplay.BacapConfigService;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderService;
import fr.euclesia.mcarchipelago.server.gameplay.FillerTrapService;
import fr.euclesia.mcarchipelago.server.gameplay.KeepInventoryService;
import fr.euclesia.mcarchipelago.server.gameplay.KnowledgeUseGate;
import fr.euclesia.mcarchipelago.server.gameplay.MobKillBridge;
import fr.euclesia.mcarchipelago.server.gameplay.RootAdvancementService;
import fr.euclesia.mcarchipelago.server.gameplay.SharedAdvancementService;
import fr.euclesia.mcarchipelago.server.gameplay.SlotReleaseService;
import fr.euclesia.mcarchipelago.server.gameplay.StartDimensionService;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderDriver;
import fr.euclesia.mcarchipelago.server.gameplay.TrapScheduler;
import fr.euclesia.mcarchipelago.server.gameplay.TrapMobService;
import fr.euclesia.mcarchipelago.server.gameplay.TrapPlatformService;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.session.APSessionCache;
import fr.euclesia.mcarchipelago.server.session.PendingChecks;
import fr.euclesia.mcarchipelago.server.runtime.APSlotGate;
import fr.euclesia.mcarchipelago.server.service.DeathLinkService;
import fr.euclesia.mcarchipelago.server.service.DeathLinkSetting;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

public final class MinecraftEventBridge {
    private static final long CONNECT_TIMEOUT_MS = 20_000L;

    private MinecraftEventBridge() {}

    public static void register() {
        // Recompute the Structure Finder's targets on the server tick (throttled) and publish them
        // to StructureFinderState for the client to render.
        StructureFinderDriver.register();

        // Station/container Knowledge gates: refuse right-clicking a block whose Knowledge is
        // still missing (see KnowledgeUseGate).
        KnowledgeUseGate.register();

        // Trap pacing: one at a time, half a minute or so apart.
        TrapScheduler.register();

        // Connect-on-join gate. A world created via the Archipelago tab stages its connection here;
        // persist it into the new world's folder, then require a live Archipelago session before the
        // world finishes loading. In normal singleplayer the client pre-flight (WorldOpenFlowsMixin)
        // has already connected before this runs, so the isConnected() check below returns early; this
        // remains the safety net for any path that reaches server start without a live session (e.g.
        // a dedicated server), where there is no client UI to fall back to.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            java.nio.file.Path worldDir = server.getWorldPath(LevelResource.ROOT);

            // Before the connect below, not after: the tag sent on connect is what makes the room
            // route other worlds' deaths here, so an operator's /aem deathlink off has to be known
            // by then or the run comes back up receiving the deaths they switched off.
            DeathLinkSetting.load(worldDir);

            APWorldConnection pending = APWorldConnection.takePending();
            if (pending != null) {
                pending.write(worldDir);
            }

            APWorldConnection connection = APWorldConnection.read(worldDir);

            // A dedicated server has no create-world screen to stage a connection, so fall back to
            // config/aem.json and seed the world from it. Deliberately only when the world has none:
            // once a world knows its slot, that file wins, so moving a save between hosts carries
            // its slot along instead of silently adopting the new host's.
            if ((connection == null || !connection.hasSlot()) && server.isDedicatedServer()) {
                AEMServerConfig config = AEMServerConfig.load();
                if (!config.hasSlot()) {
                    AEM.LOGGER.warn("No Archipelago slot configured. Set \"slot\" in {} or run "
                            + "/aem connect; the server will start without a session.", AEMServerConfig.path());
                    return;
                }
                if (!config.connectOnStart) {
                    // Idle start is the case that needs the gate MOST, not least: the server is up
                    // with no slot data at all, so close it here before returning or players could
                    // walk in and generate the world blind.
                    APSlotGate.expectSlot();
                    PendingChecks.load(worldDir);
                    // Unless the world already has its data, in which case an idle start is simply an
                    // offline one and there is nothing to hold anybody out of.
                    if (APSessionCache.restore(worldDir)) {
                        APSlotGate.trustCache();
                        AEM.LOGGER.info("connectOnStart is off; running OFFLINE on cached slot data. "
                                + "Use /aem reconnect to restore the link.");
                    } else {
                        AEM.LOGGER.info("connectOnStart is off; players are held out until /aem connect.");
                    }
                    return;
                }
                connection = config.toWorldConnection();
                connection.write(worldDir);
                AEM.LOGGER.info("Seeded this world's Archipelago slot '{}' from {}.",
                        connection.slot, AEMServerConfig.path());
            }

            if (connection == null || !connection.hasSlot()) {
                return;
            }

            // From here on this world is an Archipelago run, so the locks hold shut until its slot
            // data arrives rather than failing open (see APSlotGate).
            APSlotGate.expectSlot();
            // Checks earned offline last session are still owed; load them before anything can run.
            PendingChecks.load(worldDir);
            if (AEM.ARCHIPELAGO.client().state().isConnected()) {
                return;
            }
            // The client pre-flight may have already failed at this address; do not pay the timeout
            // a second time to learn the same thing.
            boolean skipConnect = APSessionCache.consumeOfflineStart();
            if (!skipConnect && APWorldConnector.connectBlocking(connection, CONNECT_TIMEOUT_MS)) {
                return;
            }

            // The link is down. Whether that is fatal depends entirely on whether this world has
            // ever had its slot data — the locks need the data, not the socket.
            if (APSessionCache.restore(worldDir)) {
                APSlotGate.trustCache();
                AEM.LOGGER.warn("Could not reach Archipelago at {}:{} (slot {}). Starting OFFLINE on "
                                + "this world's cached slot data: the run plays normally and checks are "
                                + "queued, but no new items can arrive until /aem reconnect succeeds.",
                        connection.address, connection.port, connection.slot);
                return;
            }

            // No cache: this world has never completed a connection, so nothing knows what its slot
            // wants. Refuse to start rather than run blind. A server that generates chunks without
            // knowing its slot places structures the slot meant to hold back, permanently and
            // invisibly — a dead server is recoverable, a spoiled world is not. The operator
            // who wants to boot anyway has connectOnStart:false, which starts idle and keeps
            // players out until /aem connect lands.
            throw new IllegalStateException("Archipelago connection failed for " + connection.address
                    + ":" + connection.port + " (slot " + connection.slot + "), and this world has no "
                    + "cached slot data because it has never connected successfully. Refusing to start: "
                    + "generating without slot data would place locked content for real. "
                    + "Set connectOnStart:false in " + AEMServerConfig.path()
                    + " to start idle and connect with /aem connect.");
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            AEMServerRuntime.setServer(server);
            // The run's shared advancement book, before any player can join and be caught up on it.
            SharedAdvancementService.load(server);
            // An offline start knows the slot without ever firing onConnected, which is where the
            // release normally hangs. Anything captured during an earlier blind window would
            // otherwise stay captured until the next real session — the fail-closed gate has to
            // stay a door, not become a one-way one, whichever way the data arrived.
            if (APSlotGate.isOffline()) {
                SlotReleaseService.releaseUnlockedContent();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SharedAdvancementService.save();
            // The queue is per world and already on disk; drop the in-memory copy so the next world
            // opened in this process does not inherit checks that belong to this one.
            PendingChecks.unload();
            APSlotGate.clear();
            AEMServerRuntime.clearServer(server);
            AEM.ARCHIPELAGO.client().close();
        });

        ServerPlayerEvents.JOIN.register(player -> {
            // A server told to start idle has no slot data, so it cannot tell locked content from
            // free. Letting someone in would have them load chunks and generate the world blind,
            // which is exactly the damage the gate exists to prevent — so they wait outside.
            if (APSlotGate.isAwaitingSlot()) {
                player.connection.disconnect(Component.translatable("message.aem.awaiting_slot"));
                return;
            }
            // Covers the connect-before-join path (e.g. main-menu connect): if the slot data is
            // already known, place the player in their start dimension before anything else.
            StartDimensionService.applyIfNeeded(player);
            // Apply the one-time BACAP reward/trophy config (no-op if already done or BACAP is off).
            BacapConfigService.applyIfNeeded(AEMServerRuntime.server());
            // Rebuild + resync the tab root for this player before scanning, so the connect-before-join
            // path still gets the goal-count root (the connect-time reload ran with no players online).
            RootAdvancementService.applyOnJoin(player);
            // Catch this player up on everything the RUN has completed before scanning them, so the
            // scan sees the shared book rather than whatever this player personally happened to have.
            SharedAdvancementService.onPlayerJoin(player);
            AdvancementBridge.scanPlayer(player);
            // Give back the soulbound Biome Finder if this slot owns it (covers first join and relog).
            BiomeFinderService.ensureGranted(player);
            // Collect the filler banked while they were away. CATCH_UP: the traps in that backlog
            // went off for whoever was in the world at the time and are not re-run at a latecomer.
            FillerTrapService.applyPending(player, FillerTrapService.Mode.CATCH_UP);
            // Hand this client the session, so its advancement overlay and tracker tab have
            // something to draw. Last, so it reflects everything the join just did.
            APStateSync.sendTo(player);
        });

        // A player who logs out between being link-killed and the death landing would otherwise keep
        // their suppression mark forever, silently swallowing their next real death's DeathLink.
        ServerPlayerEvents.LEAVE.register(player -> {
            DeathLinkService.onPlayerDisconnect(player);
            TrapScheduler.onPlayerLeave(player);
            // Their client forgets the finder bar on disconnect, so the server has to forget having
            // sent it — otherwise a reconnect gets nothing and the bar never comes back.
            StructureFinderDriver.onPlayerLeave(player);
        });

        // Hand back what death took: first the slots the keep_inventory option saved (they go back to
        // their exact indices, so they must land in an empty inventory), then the soulbound Biome
        // Finder — restored as the exact stack saved at death (keeping its tracked biome), or granted
        // fresh if none was saved. Doing the finder first would let the slot restore overwrite it.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            KeepInventoryService.restoreOnRespawn(newPlayer);
            BiomeFinderService.restoreOnRespawn(newPlayer);
        });

        // Before death drops are computed, strip from the inventory everything that must not drop —
        // the finder (saved with its tracking) and the share of slots keep_inventory keeps — so
        // vanilla only drops the rest; AFTER_RESPAWN puts both back. Always allow the death itself.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            if (entity instanceof ServerPlayer player) {
                BiomeFinderService.onDeath(player);
                KeepInventoryService.onDeath(player);
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
            // Command-shaped messages are forwarded like any other: the Archipelago server refuses
            // the dangerous ones itself, so filtering here would only stop players using the
            // commands they are allowed to use.
            String text = message.signedContent();

            // Name the speaker. One slot, several people: without this every player's chat reached
            // Archipelago as the slot with no way to tell who was talking — and since the vanilla
            // broadcast is suppressed below in favour of the echo, the name was missing in Minecraft
            // chat too.
            AEM.ARCHIPELAGO.client().send(new SayPacket(sender.getGameProfile().name() + ": " + text));
            return false;
        });
    }
}
