package fr.euclesia.mcarchipelago.server.gameplay;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes the exploding traps blow up <em>you</em> and nothing else.
 *
 * <p>A trap is a nasty surprise, not a bill: it may take your health, your footing or your afternoon,
 * but it must not take your base or your inventory off the floor. A vanilla blast does both — it digs
 * a crater and it deletes every dropped item, item frame, chest minecart and boat in range, none of
 * which the victim can undo.
 *
 * <p>So trap detonations are tagged ({@link #HARMLESS_TAG}) and re-routed here by the entity mixins.
 * The blast keeps its radius, its sound, its particles and its knockback; what changes is that blocks
 * are off the table ({@link Level.ExplosionInteraction#NONE}, no fire) and only players and mobs are
 * in the damage set, so anything that is really just property standing in the open is skipped.
 */
public final class TrapExplosions {
    /** Scoreboard tag marking an entity whose explosion must leave the world alone. Persists with it. */
    public static final String HARMLESS_TAG = "aem_trap_blast";
    /** Vanilla TNT power — the blast is as strong as ever against the victim, it just doesn't dig. */
    public static final float TNT_RADIUS = 4.0F;
    /** Vanilla creeper power. */
    public static final float CREEPER_RADIUS = 3.0F;

    /** Spares blocks, and spares everything that is not a player or a mob (dropped items above all). */
    private static final ExplosionDamageCalculator HARMLESS = new ExplosionDamageCalculator() {
        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos,
                                          BlockState state, float power) {
            return false;
        }

        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return entity instanceof Player || entity instanceof Mob;
        }
    };

    private TrapExplosions() {}

    /** Marks {@code entity} so its explosion is routed through {@link #detonate}. */
    public static void markHarmless(Entity entity) {
        entity.addTag(HARMLESS_TAG);
    }

    /** Whether {@code entity}'s explosion must spare the world. */
    public static boolean isHarmless(Entity entity) {
        return entity != null && entity.entityTags().contains(HARMLESS_TAG);
    }

    /** The blast the victim feels, minus the one they'd have to repair. */
    public static void detonate(ServerLevel level, Entity source, float radius) {
        level.explode(source, null, HARMLESS, source.getX(), source.getY(0.0625), source.getZ(),
                radius, false, Level.ExplosionInteraction.NONE);
    }
}
