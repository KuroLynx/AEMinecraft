package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;

public final class AdvancementBridge {
    private AdvancementBridge() {}

    public static void onCompleted(ServerPlayer player, String advancementId) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return;
        }

        // The check result is reported back through Archipelago's PrintJSON (rendered to chat by
        // ArchipelagoChatListener), so no local confirmation message is needed here.
        AEM.ARCHIPELAGO.gateway().checkLocation(advancementId);

        // Count this toward the tab-root goal progress (one criterion per completed advancement),
        // but only for real advancement checks this seed — not the root tile itself.
        if (!APTrackerRegistry.TAB_ROOT_ID.equals(advancementId)
                && AEM.ARCHIPELAGO.client().registries().apLocations().isActiveLocation(advancementId)) {
            RootAdvancementService.awardProgress(player);
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

        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
            if (player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                onCompleted(player, advancement.id().toString());
            }
        }
    }
}
