package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;

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
        boolean mainGoalComplete = switch (slotData.goal()) {
            case KILL_ENDER_DRAGON -> killedBosses.contains("minecraft:ender_dragon") || killedBosses.contains("ender_dragon");
            case KILL_ALL_BOSSES -> killedBosses.containsAll(slotData.bossSelection());
        };
        boolean deathListComplete = !slotData.deathList() || killedDeathListMobs.containsAll(slotData.deathListMobs());

        if (mainGoalComplete && deathListComplete) {
            AEM.ARCHIPELAGO.gateway().markGoalReached();
        }
    }

    private static String stripMinecraftNamespace(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
