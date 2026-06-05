package fr.euclesia.mcarchipelago.server.gameplay;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Spawns the "inert" mobs used by trap items (the {@code aww_man} creeper and the {@code paris_games_week}
 * crowd). A trap mob is tagged with {@link #TRAP_TAG} so it:
 * <ul>
 *   <li>bypasses the Archipelago mob-spawn lock ({@link MobSpawnLockService}) — a locked mob can still
 *       be conjured as a trap;</li>
 *   <li>drops no loot ({@code LivingEntityMixin}) and no experience ({@link Mob#skipDropExperience()});</li>
 *   <li>grants the killer no advancement or score ({@code ServerPlayerMixin}) and is ignored by the
 *       Archipelago kill checks ({@link MobKillBridge}).</li>
 * </ul>
 * Each trap mob is also force-despawned after {@link #LIFETIME_MS} so a trap can't permanently litter the
 * world. The tag is set inside the spawn consumer, i.e. before the entity is added, so the lock-bypass
 * check sees it at the {@code ServerLevel#addEntity} chokepoint.
 */
public final class TrapMobService {
    /** Scoreboard tag marking an entity as a trap mob (persists with the entity). */
    public static final String TRAP_TAG = "aem_trap_mob";
    /** How long a trap mob survives before being force-removed. */
    private static final long LIFETIME_MS = 30_000L;

    private static final List<Tracked> TRACKED = new ArrayList<>();

    private record Tracked(Entity entity, long expiry) {}

    private TrapMobService() {}

    /** Registers the despawn tick. Call once during server-bridge setup. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        if (TRACKED.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Tracked> it = TRACKED.iterator();
        while (it.hasNext()) {
            Tracked tracked = it.next();
            if (!tracked.entity().isAlive()) {
                it.remove();
            } else if (now >= tracked.expiry()) {
                tracked.entity().discard();
                it.remove();
            }
        }
    }

    /** Force-removes every live trap mob (e.g. when the victim dies). */
    public static void discardAll() {
        for (Tracked tracked : TRACKED) {
            if (tracked.entity().isAlive()) {
                tracked.entity().discard();
            }
        }
        TRACKED.clear();
    }

    /** Whether {@code entity} is one of our trap mobs. */
    public static boolean isTrapMob(Entity entity) {
        return entity != null && entity.entityTags().contains(TRAP_TAG);
    }

    /**
     * Spawns a single inert trap mob of {@code type} at {@code pos}, applying {@code extra} configuration
     * (e.g. igniting a creeper) before it enters the world. Returns the spawned mob, or {@code null} if
     * the spawn was refused.
     */
    public static <T extends Mob> T spawn(EntityType<T> type, ServerLevel level, BlockPos pos, Consumer<T> extra) {
        T mob = type.spawn(level, configured -> {
            configured.addTag(TRAP_TAG);
            configured.skipDropExperience();
            configured.setPersistenceRequired();
            // Stop zombies from calling in untracked reinforcements that would outlive the trap.
            AttributeInstance reinforcements = configured.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
            if (reinforcements != null) {
                reinforcements.setBaseValue(0.0);
            }
            if (extra != null) {
                extra.accept(configured);
            }
        }, pos, EntitySpawnReason.EVENT, true, false);
        if (mob != null) {
            TRACKED.add(new Tracked(mob, System.currentTimeMillis() + LIFETIME_MS));
        }
        return mob;
    }
}
