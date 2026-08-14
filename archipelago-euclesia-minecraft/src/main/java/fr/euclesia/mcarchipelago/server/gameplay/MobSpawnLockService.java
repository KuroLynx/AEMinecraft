package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.runtime.APSlotGate;
import net.minecraft.world.entity.Entity;

import java.util.List;

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
    /**
     * The mobs a raid wave spawns, mirroring vanilla's {@code Raid.RaiderType} enum — the table the
     * waves actually draw from.
     *
     * <p>Deliberately NOT the {@code #minecraft:raiders} tag, which also carries the Illusioner: it
     * is a member of that tag but never spawns in a raid, so requiring it would gate raids behind a
     * mob the raid never asks for. Hard-coded rather than read from {@code Raid.RaiderType} because
     * that enum is package-private with a private entity type — and the wave table is hard-coded in
     * {@code Raid} too, so no datapack can change this set.
     */
    private static final List<String> RAID_MOB_IDS = List.of(
            "minecraft:pillager",
            "minecraft:vindicator",
            "minecraft:evoker",
            "minecraft:witch",
            "minecraft:ravager");

    private MobSpawnLockService() {}

    /**
     * Whether any raid mob is still spawn-locked, i.e. a raid could not play out if it started.
     *
     * <p>A raid wave only clears once its raiders are dead, so a single locked member leaves a wave
     * that can never be completed and a raid that can never be won — the village siege just hangs.
     * {@code BadOmenMobEffectMixin} uses this to hold the Bad Omen -> Raid Omen conversion back
     * until every raider is unlocked.
     */
    public static boolean isAnyRaidMobLocked() {
        return RAID_MOB_IDS.stream().anyMatch(MobSpawnLockService::isMobLocked);
    }

    public static boolean shouldBlockSpawn(Entity entity) {
        // Trap-conjured mobs are exempt: a trap can summon an otherwise-locked mob on purpose.
        if (TrapMobService.isTrapMob(entity)) {
            return false;
        }
        return isMobLocked(AEMServerRuntime.entityGameId(entity));
    }

    /**
     * The game id of the first still-locked mob anywhere in this entity's rider stack, or {@code null}
     * when the whole stack may spawn.
     *
     * <p>A jockey is one spawn wearing two entities: a Parched riding a Camel Husk arrives as a mount
     * plus a passenger, and every add path hands them over one at a time. Judged individually the pair
     * gets split — the unlocked half enters the world while the locked half is refused, leaving a
     * riderless mount (or, on the worldgen path, a rider whose vehicle never came). Judged as a stack,
     * a single locked member holds the whole thing back, which is the only answer that keeps the spawn
     * intact for later.
     */
    public static String firstLockedInStack(Entity entity) {
        return entity.getSelfAndPassengers()
                .filter(MobSpawnLockService::shouldBlockSpawn)
                .findFirst()
                .map(AEMServerRuntime::entityGameId)
                .orElse(null);
    }

    /** Whether any mob in this entity's rider stack is still spawn-locked. */
    public static boolean shouldBlockStack(Entity entity) {
        return firstLockedInStack(entity) != null;
    }

    /** Id-based variant, for callers that gate on a specific mob (e.g. the Ender Dragon fight). */
    public static boolean isMobLocked(String mobGameId) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            // Fail CLOSED while a slot is expected but unknown: we cannot yet tell whether this mob
            // is one the slot holds back, and a mob wrongly allowed to spawn is a leak we cannot
            // take back. A blocked spawn costs nothing — the mob simply spawns later.
            return APSlotGate.isAwaitingSlot();
        }
        return AEM.ARCHIPELAGO.client().registries().apMobs().isSpawnLocked(mobGameId);
    }
}
