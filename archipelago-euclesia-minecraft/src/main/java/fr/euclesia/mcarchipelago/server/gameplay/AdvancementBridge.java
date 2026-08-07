package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class AdvancementBridge {
    private AdvancementBridge() {}

    public static void onCompleted(ServerPlayer player, String advancementId) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return;
        }

        // Recipe-unlock "advancements" (minecraft:recipes/...) fire constantly and are never
        // randomized locations, so skip them before any lookup/network work.
        if (advancementId.startsWith("minecraft:recipes/")) {
            return;
        }

        // Everyone on the server shares one book, so hand this completion to the other players. The
        // return value is what makes a location report exactly once: it is true only for the player
        // who actually got there, false for the copies we then award to everybody else and for a
        // late joiner catching up on a location the run sent hours ago.
        if (!SharedAdvancementService.onCompleted(player, advancementId)) {
            return;
        }

        // The check result is reported back through Archipelago's PrintJSON (rendered to chat by
        // ArchipelagoChatListener), so no local confirmation message is needed here.
        boolean sent = AEM.ARCHIPELAGO.gateway().checkLocation(advancementId);
        // Intentional [AEM-DIAG] server-log trace (server-side only, never shown to players): records
        // each completion's id, whether a check was sent, and the location-mapping state so check
        // problems can be diagnosed straight from the Minecraft log.
        AEM.LOGGER.info("[AEM-DIAG] onCompleted id='{}' sent={} anyLocationsLoaded={} idIsActiveLocation={}",
                advancementId, sent,
                AEM.ARCHIPELAGO.client().registries().apLocations().hasLocations(),
                AEM.ARCHIPELAGO.client().registries().apLocations().isActiveLocation(advancementId));

        // Count this toward the tab-root goal progress (one criterion per completed advancement),
        // but only for real advancement checks this seed — not the root tile itself.
        if (!APTrackerRegistry.TAB_ROOT_ID.equals(advancementId)
                && AEM.ARCHIPELAGO.client().registries().apLocations().isActiveLocation(advancementId)) {
            RootAdvancementService.syncProgress(player);
            // The advancement count is part of the win condition, so re-check the goal here too — not
            // just on boss kills — or completing the last required advancement wouldn't trigger the win.
            GoalTracker.evaluate();
        }
    }

    /**
     * Re-sends every online player's advancements so the visibility evaluator runs again with
     * the now-known Archipelago location set (see {@link fr.euclesia.mcarchipelago.mixin.AdvancementVisibilityEvaluatorMixin}).
     * Called on connect: until then visibility falls back to revealing everything, so this is
     * what makes non-check advancements disappear once the slot data is loaded.
     */
    public static void reloadOnlinePlayers() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }

        server.execute(() -> {
            ServerAdvancementManager manager = server.getAdvancements();
            server.getPlayerList().getPlayers().forEach(player -> player.getAdvancements().reload(manager));
        });
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

        // Collect every completed advancement and send the checks as a single LocationChecks packet
        // rather than one packet per advancement — a join/connect scan can touch hundreds at once.
        List<String> completed = new ArrayList<>();
        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
            if (!player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                continue;
            }
            String advancementId = advancement.id().toString();
            if (advancementId.startsWith("minecraft:recipes/")) {
                continue;
            }
            completed.add(advancementId);
        }

        int sent = AEM.ARCHIPELAGO.gateway().checkLocationsByGameId(completed);
        AEM.LOGGER.info("[AEM-DIAG] scanPlayer sent {} checks from {} completed advancements", sent, completed.size());

        // Fold whatever this player already held into the run's shared book, so the book is the
        // UNION of everyone's progress rather than only what was earned since sharing began. This is
        // the path that matters for an existing world going multiplayer, or for a player whose file
        // carries progress the book never saw; anything already in the book is a no-op, and anything
        // new propagates to the other players from here.
        for (String advancementId : completed) {
            SharedAdvancementService.onCompleted(player, advancementId);
        }

        // Re-evaluate the advancement-count goal progress once after the batch (syncProgress is an
        // idempotent recompute, so a single call covers every advancement just folded in above).
        RootAdvancementService.syncProgress(player);
        GoalTracker.evaluate();
    }
}
