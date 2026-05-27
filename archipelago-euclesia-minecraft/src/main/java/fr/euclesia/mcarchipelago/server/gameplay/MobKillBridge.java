package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.world.entity.LivingEntity;

public final class MobKillBridge {
    private MobKillBridge() {}

    public static void onMobKilled(LivingEntity killed) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return;
        }

        String mobGameId = AEMServerRuntime.entityGameId(killed);
        if (AEM.ARCHIPELAGO.gateway().checkTrackedMob(mobGameId)) {
            AEM.LOGGER.info("Archipelago mob check: {}", mobGameId);
        }

        GoalTracker.recordMobKill(mobGameId);
        GoalTracker.evaluate();
    }
}
