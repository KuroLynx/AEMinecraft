package fr.euclesia.mcarchipelago.content;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * A plain, subclass-able {@link MobEffect} whose only job is to expose a public constructor (the base
 * class's is {@code protected}) so {@link FillerBuffEffects} can register the filler buffs — most of
 * which are nothing but a bundle of attribute modifiers added via {@link #addAttributeModifier}. The
 * tick-driven filler buffs (regeneration, saturation, absorption) subclass this and override the tick
 * hooks.
 */
public class AttributeBuffMobEffect extends MobEffect {
    public AttributeBuffMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }
}
