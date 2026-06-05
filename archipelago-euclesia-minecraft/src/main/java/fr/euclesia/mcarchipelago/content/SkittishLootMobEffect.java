package fr.euclesia.mcarchipelago.content;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * "Skittish loot": while active, any dropped item near the victim flees away from them, so they can't
 * collect anything until it wears off (pickup itself is blocked in {@code ItemEntityMixin}). Applied by
 * the {@code item_fear} trap, which first scatters the player's whole inventory (see {@code TrapEffects}).
 */
public class SkittishLootMobEffect extends MobEffect {
    /** How close (blocks, beyond the player's hitbox) an item must be before it bolts — roughly pickup range. */
    private static final double FLEE_RADIUS = 2.0;
    /** Horizontal flee speed; kept gentle so items edge away rather than rocket off.*/
    private static final double FLEE_SPEED = 0.22;
    /** Only nudge every N ticks: items coast (with friction) in between, which slashes velocity-packet spam. */
    private static final int PUSH_INTERVAL = 5;

    public SkittishLootMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % PUSH_INTERVAL == 0;
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        if (!(entity instanceof ServerPlayer player)) {
            return true;
        }
        AABB box = player.getBoundingBox().inflate(FLEE_RADIUS);
        Vec3 playerPos = player.position();
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity item : items) {
            Vec3 away = item.position().subtract(playerPos);
            if (away.lengthSqr() < 1.0e-4) {
                away = new Vec3(level.getRandom().nextDouble() - 0.5, 0.0, level.getRandom().nextDouble() - 0.5);
            }
            away = away.normalize().scale(FLEE_SPEED);
            // Only nudge horizontally; keep the item's own vertical motion so gravity still settles it.
            item.setDeltaMovement(away.x, item.getDeltaMovement().y, away.z);
        }
        return true;
    }
}
