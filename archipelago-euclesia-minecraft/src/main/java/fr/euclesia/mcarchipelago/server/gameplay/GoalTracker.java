package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;

public final class GoalTracker {
    private static final Set<String> killedBosses = new HashSet<>();
    private static final Set<String> killedDeathListMobs = new HashSet<>();

    private GoalTracker() {}

    public static void recordMobKill(String mobGameId) {
        APSlotData slotData = AEM.ARCHIPELAGO.client().state().parsedSlotData();
        if (slotData.bossSelection().contains(mobGameId) || slotData.bossSelection().contains(stripMinecraftNamespace(mobGameId))) {
            killedBosses.add(mobGameId);
            killedBosses.add(stripMinecraftNamespace(mobGameId));
        }

        if (slotData.deathListMobs().contains(mobGameId)) {
            killedDeathListMobs.add(mobGameId);
        }
    }

    public static void evaluate() {
        APSlotData slotData = AEM.ARCHIPELAGO.client().state().parsedSlotData();
        // The win condition is "kill every boss in boss_list" (the slot's selected bosses). The
        // separate KILL_ENDER_DRAGON/KILL_ALL_BOSSES enum is not part of the slot data, so a dragon-
        // only seed simply has boss_list = [ender_dragon] and this collapses to "kill the dragon".
        boolean mainGoalComplete = killedBosses.containsAll(slotData.bossSelection());
        boolean deathListComplete = !slotData.deathList() || killedDeathListMobs.containsAll(slotData.deathListMobs());
        // advancements_required is cumulative with the boss kills: ALL active conditions must be met
        // to win (mirrors the apworld completion_condition). Without this the goal fired on bosses
        // alone, releasing the slot before the required advancements were completed.
        boolean advancementsComplete = advancementGoalMet();

        if (mainGoalComplete && deathListComplete && advancementsComplete) {
            AEM.ARCHIPELAGO.gateway().markGoalReached();
        }
    }

    /**
     * Whether the advancement side of the goal is met: at least {@code requiredAdvancements} active
     * advancement checks are completed. The count is per-player (advancements are), so we take the
     * best among online players — for the usual single-player slot that's just the player.
     */
    private static boolean advancementGoalMet() {
        int required = RootAdvancementService.requiredAdvancements();
        if (required <= 0) {
            return true;
        }
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return false;
        }
        int best = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            best = Math.max(best, RootAdvancementService.completedGoalAdvancements(player));
        }
        return best >= required;
    }

    private static String stripMinecraftNamespace(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
