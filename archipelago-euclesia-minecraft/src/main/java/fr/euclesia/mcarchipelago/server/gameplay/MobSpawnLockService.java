package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.world.entity.Entity;

/**
 * Decides whether an entity is forbidden from spawning by the Archipelago mob-spawn-lock option:
 * a locked mob may not enter the world until its unlock item has been received.
 *
 * <p>Queried from {@code ServerLevelMixin} at the single {@code ServerLevel#addEntity} chokepoint,
 * which every fresh spawn funnels through (natural spawns, spawners, spawn eggs, {@code /summon},
 * breeding, mob conversions, reinforcements) — so a locked mob is blocked no matter how it would
 * have appeared. Entities loaded from disk bypass {@code addEntity}, so existing mobs are untouched.
 */
public final class MobSpawnLockService {
    private MobSpawnLockService() {}

    public static boolean shouldBlockSpawn(Entity entity) {
        // Trap-conjured mobs are exempt: a trap can summon an otherwise-locked mob on purpose.
        if (TrapMobService.isTrapMob(entity)) {
            return false;
        }
        return isMobLocked(AEMServerRuntime.entityGameId(entity));
    }

    /** Id-based variant, for callers that gate on a specific mob (e.g. the Ender Dragon fight). */
    public static boolean isMobLocked(String mobGameId) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return false;
        }
        return AEM.ARCHIPELAGO.client().registries().apMobs().isSpawnLocked(mobGameId);
    }
}
