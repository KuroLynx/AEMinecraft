package fr.euclesia.mcarchipelago.content;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * "Butterfingers": while active, the victim fumbles whatever they are currently holding once a second,
 * so they cannot keep an item in hand for the duration. Applied by the {@code slippery_fingers} trap
 * (see {@code TrapEffects}). Server-side only — {@link #applyEffectTick} only runs on a {@link ServerLevel}.
 */
public class ButterfingersMobEffect extends MobEffect {
    public ButterfingersMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    /** Fumble continuously: anything that reaches the player's hand is knocked out next tick. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        if (entity instanceof ServerPlayer player && !player.getMainHandItem().isEmpty()) {
            player.drop(true); // tosses the selected hotbar stack
            // Force a full menu resync so the held item visibly vanishes (incremental broadcast desyncs).
            player.containerMenu.sendAllDataToRemote();
        }
        return true;
    }
}
