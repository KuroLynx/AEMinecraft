package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

public final class MobKillBridge {
    private MobKillBridge() {}

    public static void onMobKilled(LivingEntity killed, DamageSource source) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return;
        }

        // Only the player's own kills count — not mob-vs-mob or environmental deaths.
        if (!killedByPlayer(killed, source)) {
            return;
        }

        String mobGameId = AEMServerRuntime.entityGameId(killed);
        if (AEM.ARCHIPELAGO.gateway().checkTrackedMob(mobGameId)) {
            AEM.LOGGER.info("Archipelago mob check: {}", mobGameId);
        }

        GoalTracker.recordMobKill(mobGameId);
        GoalTracker.evaluate();
    }

    /**
     * Mirrors vanilla kill attribution: a direct/indirect attacker that is the player (melee or a
     * projectile they fired), or a kill credited to the player (e.g. a mob finished off by fall or
     * fire shortly after the player struck it).
     */
    private static boolean killedByPlayer(LivingEntity killed, DamageSource source) {
        if (source != null && source.getEntity() instanceof ServerPlayer) {
            return true;
        }
        return killed.getKillCredit() instanceof ServerPlayer;
    }
}
