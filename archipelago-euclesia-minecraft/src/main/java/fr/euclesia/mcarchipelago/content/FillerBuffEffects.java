package fr.euclesia.mcarchipelago.content;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers the mod's custom status effects for the <em>filler</em> items (see
 * {@code minecraft_aem/filler.py} and {@code FillerBuffService}). Each buff mirrors a classic potion
 * effect (or a fun extra), but is registered under the {@code aem:} namespace with its own id rather
 * than reusing the vanilla {@code minecraft:} effect. That distinction is the whole point: a real
 * {@link MobEffect} shows up in the HUD/inventory with an icon and a ticking-down duration — so the
 * player can see the buff and how long it lasts — while its custom id can't satisfy a BACAP effect
 * advancement predicate (which matches specific {@code minecraft:} effects) and so can't hand out free
 * checks or desync the logic. Registration runs at mod init, before the registries freeze.
 */
public final class FillerBuffEffects {
    /** Slot-data buff key -> registered effect, filled as each is registered. */
    private static final Map<String, Holder<MobEffect>> BY_KEY = new HashMap<>();

    // --- Classic potion analogues ---
    public static final Holder<MobEffect> SPEED = register("speed",
            beneficial(0x7CAFC6).addAttributeModifier(Attributes.MOVEMENT_SPEED, id("speed"), 0.20, Operation.ADD_MULTIPLIED_TOTAL));
    public static final Holder<MobEffect> HASTE = register("haste",
            beneficial(0xD9C043).addAttributeModifier(Attributes.BLOCK_BREAK_SPEED, id("haste"), 0.25, Operation.ADD_MULTIPLIED_TOTAL));
    public static final Holder<MobEffect> STRENGTH = register("strength",
            beneficial(0x932423).addAttributeModifier(Attributes.ATTACK_DAMAGE, id("strength"), 3.0, Operation.ADD_VALUE));
    public static final Holder<MobEffect> JUMP_BOOST = register("jump_boost",
            beneficial(0x22FF4C).addAttributeModifier(Attributes.JUMP_STRENGTH, id("jump_boost"), 0.10, Operation.ADD_VALUE));
    public static final Holder<MobEffect> REGENERATION = register("regeneration", regeneration(0xCD5CAB));
    public static final Holder<MobEffect> ABSORPTION = register("absorption",
            absorption(0x2552A5).addAttributeModifier(Attributes.MAX_ABSORPTION, id("absorption"), 4.0, Operation.ADD_VALUE));
    public static final Holder<MobEffect> HEALTH_BOOST = register("health_boost",
            beneficial(0xF87D23).addAttributeModifier(Attributes.MAX_HEALTH, id("health_boost"), 4.0, Operation.ADD_VALUE));
    public static final Holder<MobEffect> LUCK = register("luck",
            beneficial(0x59C106).addAttributeModifier(Attributes.LUCK, id("luck"), 5.0, Operation.ADD_VALUE));
    public static final Holder<MobEffect> TOUGHNESS = register("resistance",
            beneficial(0x99453A)
                    .addAttributeModifier(Attributes.ARMOR, id("resistance"), 8.0, Operation.ADD_VALUE)
                    .addAttributeModifier(Attributes.ARMOR_TOUGHNESS, id("resistance"), 4.0, Operation.ADD_VALUE)
                    .addAttributeModifier(Attributes.KNOCKBACK_RESISTANCE, id("resistance"), 0.2, Operation.ADD_VALUE));

    // --- Fun extras ---
    public static final Holder<MobEffect> REACH = register("reach",
            beneficial(0x4A90D9)
                    .addAttributeModifier(Attributes.BLOCK_INTERACTION_RANGE, id("reach"), 2.0, Operation.ADD_VALUE)
                    .addAttributeModifier(Attributes.ENTITY_INTERACTION_RANGE, id("reach"), 2.0, Operation.ADD_VALUE));
    public static final Holder<MobEffect> STEP_ASSIST = register("step_assist",
            beneficial(0x8B6F47).addAttributeModifier(Attributes.STEP_HEIGHT, id("step_assist"), 1.0, Operation.ADD_VALUE));
    public static final Holder<MobEffect> LOW_GRAVITY = register("low_gravity",
            beneficial(0xB0E0E6)
                    .addAttributeModifier(Attributes.GRAVITY, id("low_gravity"), -0.5, Operation.ADD_MULTIPLIED_TOTAL)
                    // Moon boots: negate fall damage while floating. -1.0 multiplied-total drives the
                    // fall-damage multiplier to base * (1 - 1) = 0. Auto-removed when the buff ends.
                    .addAttributeModifier(Attributes.FALL_DAMAGE_MULTIPLIER, id("low_gravity"), -1.0, Operation.ADD_MULTIPLIED_TOTAL));
    public static final Holder<MobEffect> SATURATION = register("saturation", saturation(0xF87D23));
    public static final Holder<MobEffect> MINI = register("mini",
            beneficial(0xE07BC0).addAttributeModifier(Attributes.SCALE, id("mini"), -0.4, Operation.ADD_MULTIPLIED_TOTAL));
    public static final Holder<MobEffect> GIANT = register("giant",
            beneficial(0x7B3FE0).addAttributeModifier(Attributes.SCALE, id("giant"), 0.5, Operation.ADD_MULTIPLIED_TOTAL));

    private FillerBuffEffects() {}

    /** The registered effect for a slot-data buff key, or {@code null} if the key is unknown. */
    public static Holder<MobEffect> byKey(String key) {
        return BY_KEY.get(key);
    }

    private static AttributeBuffMobEffect beneficial(int color) {
        return new AttributeBuffMobEffect(MobEffectCategory.BENEFICIAL, color);
    }

    /** Regeneration: heals half a heart every 50 ticks while active (mirrors vanilla Regeneration I). */
    private static MobEffect regeneration(int color) {
        return new AttributeBuffMobEffect(MobEffectCategory.BENEFICIAL, color) {
            @Override
            public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
                int period = Math.max(50 >> amplifier, 1);
                return duration % period == 0;
            }

            @Override
            public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
                if (entity.getHealth() < entity.getMaxHealth()) {
                    entity.heal(1.0f);
                }
                return true;
            }
        };
    }

    /** Saturation: refills hunger and saturation every tick while active (mirrors vanilla Saturation). */
    private static MobEffect saturation(int color) {
        return new AttributeBuffMobEffect(MobEffectCategory.BENEFICIAL, color) {
            @Override
            public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
                return true;
            }

            @Override
            public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
                if (entity instanceof ServerPlayer player) {
                    player.getFoodData().eat(amplifier + 1, 1.0f);
                }
                return true;
            }
        };
    }

    /** Absorption: grants extra yellow hearts on top of the raised MAX_ABSORPTION (mirrors vanilla). */
    private static MobEffect absorption(int color) {
        return new AttributeBuffMobEffect(MobEffectCategory.BENEFICIAL, color) {
            @Override
            public void onEffectStarted(LivingEntity entity, int amplifier) {
                super.onEffectStarted(entity, amplifier);
                entity.setAbsorptionAmount(Math.max(entity.getAbsorptionAmount(), 4.0f * (amplifier + 1)));
            }
        };
    }

    private static Identifier id(String key) {
        return Identifier.fromNamespaceAndPath(AEM.MOD_ID, "buff/" + key);
    }

    private static Holder<MobEffect> register(String key, MobEffect effect) {
        Holder<MobEffect> holder = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
                Identifier.fromNamespaceAndPath(AEM.MOD_ID, key), effect);
        BY_KEY.put(key, holder);
        return holder;
    }

    /** Loads this class so its static registration runs. Call once during mod init. */
    public static void register() {
        // Touching the fields above triggers registration on class load; nothing else to do.
    }
}
