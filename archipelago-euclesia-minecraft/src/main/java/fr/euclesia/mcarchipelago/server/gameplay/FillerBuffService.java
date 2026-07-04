package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies the temporary buffs granted by filler items (see {@code minecraft_aem/filler.py} and
 * {@link FillerTrapService}). Each buff is reproduced <em>mechanically</em> — attribute modifiers and
 * per-tick logic — and never as a {@code minecraft:} {@link net.minecraft.world.effect.MobEffect}, so
 * receiving one can neither pop an item advancement (no item is granted) nor an effect advancement (no
 * {@code effects_changed} fires). That is the whole point of moving filler off Minecraft items.
 *
 * <p>Durations <b>stack</b>: each received copy adds its seconds to that buff's remaining timer. State
 * is per-player and in-memory (buffs are short-lived; a restart clearing them is fine). Attribute
 * modifiers are <em>transient</em> — they never save to disk, and we re-assert them each tick so a
 * respawn (which rebuilds the player's attributes) doesn't drop an active buff. All work runs on the
 * server thread.
 */
public final class FillerBuffService {
    private static final int TPS = 20;
    /** Regeneration heals this much every {@link #REGEN_PERIOD} ticks while active. */
    private static final float REGEN_AMOUNT = 1.0f;
    private static final int REGEN_PERIOD = 50;
    /** Saturation tops the player's hunger up this often. */
    private static final int SATURATION_PERIOD = 20;

    /** Remaining buff time per player, in ticks. Empty inner maps are pruned. */
    private static final Map<UUID, EnumMap<Buff, Integer>> REMAINING = new HashMap<>();
    private static long serverTicks;

    private FillerBuffService() {}

    /** A single attribute modifier a buff applies while active. */
    private record Mod(Holder<Attribute> attribute, double amount, Operation operation) {}

    private static Mod mod(Holder<Attribute> attribute, double amount, Operation operation) {
        return new Mod(attribute, amount, operation);
    }

    /** Per-tick effects that aren't expressible as a static attribute modifier. */
    private enum TickKind { NONE, REGEN, SATURATION }

    /**
     * The buff catalogue, keyed by the slot-data buff key. Magnitudes roughly mirror the level-I
     * potion effect they stand in for; the "fun extras" use attributes vanilla potions don't touch.
     */
    private enum Buff {
        // --- Classic potion analogues ---
        SPEED("speed", TickKind.NONE, mod(Attributes.MOVEMENT_SPEED, 0.20, Operation.ADD_MULTIPLIED_TOTAL)),
        HASTE("haste", TickKind.NONE, mod(Attributes.BLOCK_BREAK_SPEED, 0.25, Operation.ADD_MULTIPLIED_TOTAL)),
        STRENGTH("strength", TickKind.NONE, mod(Attributes.ATTACK_DAMAGE, 3.0, Operation.ADD_VALUE)),
        JUMP_BOOST("jump_boost", TickKind.NONE, mod(Attributes.JUMP_STRENGTH, 0.10, Operation.ADD_VALUE)),
        REGENERATION("regeneration", TickKind.REGEN),
        ABSORPTION("absorption", TickKind.NONE, mod(Attributes.MAX_ABSORPTION, 4.0, Operation.ADD_VALUE)),
        HEALTH_BOOST("health_boost", TickKind.NONE, mod(Attributes.MAX_HEALTH, 4.0, Operation.ADD_VALUE)),
        LUCK("luck", TickKind.NONE, mod(Attributes.LUCK, 5.0, Operation.ADD_VALUE)),
        TOUGHNESS("resistance", TickKind.NONE,
                mod(Attributes.ARMOR, 8.0, Operation.ADD_VALUE),
                mod(Attributes.ARMOR_TOUGHNESS, 4.0, Operation.ADD_VALUE),
                mod(Attributes.KNOCKBACK_RESISTANCE, 0.2, Operation.ADD_VALUE)),
        // --- Fun extras ---
        REACH("reach", TickKind.NONE,
                mod(Attributes.BLOCK_INTERACTION_RANGE, 2.0, Operation.ADD_VALUE),
                mod(Attributes.ENTITY_INTERACTION_RANGE, 2.0, Operation.ADD_VALUE)),
        STEP_ASSIST("step_assist", TickKind.NONE, mod(Attributes.STEP_HEIGHT, 1.0, Operation.ADD_VALUE)),
        LOW_GRAVITY("low_gravity", TickKind.NONE, mod(Attributes.GRAVITY, -0.5, Operation.ADD_MULTIPLIED_TOTAL)),
        SATURATION("saturation", TickKind.SATURATION),
        MINI("mini", TickKind.NONE, mod(Attributes.SCALE, -0.4, Operation.ADD_MULTIPLIED_TOTAL)),
        GIANT("giant", TickKind.NONE, mod(Attributes.SCALE, 0.5, Operation.ADD_MULTIPLIED_TOTAL));

        final String key;
        final TickKind tickKind;
        final List<Mod> mods;

        Buff(String key, TickKind tickKind, Mod... mods) {
            this.key = key;
            this.tickKind = tickKind;
            this.mods = List.of(mods);
        }

        static Buff byKey(String key) {
            for (Buff buff : values()) {
                if (buff.key.equals(key)) {
                    return buff;
                }
            }
            return null;
        }

        /** A stable, per-buff modifier id for its {@code i}-th attribute (transient; never saved). */
        Identifier modifierId(int i) {
            return Identifier.fromNamespaceAndPath(AEM.MOD_ID, "buff/" + key + "/" + i);
        }
    }

    /** Registers the buff tick. Call once during server-bridge setup. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(FillerBuffService::tick);
    }

    /**
     * Grants (or extends) a buff on {@code player} for {@code seconds}. Duration stacks onto whatever is
     * already running. Called from {@link FillerTrapService} when a buff filler item is received.
     */
    public static void applyBuff(ServerPlayer player, String key, int seconds) {
        Buff buff = Buff.byKey(key);
        if (buff == null) {
            AEM.LOGGER.warn("Unknown filler buff '{}'", key);
            return;
        }
        EnumMap<Buff, Integer> active = REMAINING.computeIfAbsent(player.getUUID(), id -> new EnumMap<>(Buff.class));
        int remaining = active.merge(buff, seconds * TPS, Integer::sum);
        // Take effect immediately rather than waiting for the next tick.
        ensureModifiers(player, buff);
        if (buff == Buff.ABSORPTION) {
            grantAbsorption(player);
        }
        // Overlay (action bar) note so the player sees the buff and its remaining seconds.
        player.sendSystemMessage(Component.translatable("filler.aem.buff.applied",
                Component.translatable("filler.aem.buff." + key), remaining / TPS), true);
    }

    private static void tick(MinecraftServer server) {
        serverTicks++;
        if (REMAINING.isEmpty()) {
            return;
        }
        // Copy the key set: expired players are pruned from REMAINING inside the loop.
        for (UUID id : new ArrayList<>(REMAINING.keySet())) {
            EnumMap<Buff, Integer> active = REMAINING.get(id);
            if (active == null) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            Iterator<Map.Entry<Buff, Integer>> it = active.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Buff, Integer> entry = it.next();
                Buff buff = entry.getKey();
                int left = entry.getValue() - 1;
                if (left <= 0) {
                    if (player != null) {
                        removeModifiers(player, buff);
                    }
                    it.remove();
                } else {
                    entry.setValue(left);
                    if (player != null) {
                        ensureModifiers(player, buff);
                        runTick(player, buff);
                    }
                }
            }
            if (active.isEmpty()) {
                REMAINING.remove(id);
            }
        }
    }

    /** Adds each of a buff's attribute modifiers to the player if not already present. */
    private static void ensureModifiers(ServerPlayer player, Buff buff) {
        for (int i = 0; i < buff.mods.size(); i++) {
            Mod mod = buff.mods.get(i);
            AttributeInstance instance = player.getAttribute(mod.attribute());
            if (instance == null) {
                continue;
            }
            Identifier modifierId = buff.modifierId(i);
            if (!instance.hasModifier(modifierId)) {
                instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId, mod.amount(), mod.operation()));
            }
        }
    }

    /** Removes a buff's attribute modifiers and clamps health/absorption to their new maxima. */
    private static void removeModifiers(ServerPlayer player, Buff buff) {
        for (int i = 0; i < buff.mods.size(); i++) {
            AttributeInstance instance = player.getAttribute(buff.mods.get(i).attribute());
            if (instance != null) {
                instance.removeModifier(buff.modifierId(i));
            }
        }
        if (buff == Buff.HEALTH_BOOST && player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
        if (buff == Buff.ABSORPTION) {
            float maxAbsorption = (float) player.getAttributeValue(Attributes.MAX_ABSORPTION);
            if (player.getAbsorptionAmount() > maxAbsorption) {
                player.setAbsorptionAmount(maxAbsorption);
            }
        }
    }

    private static void runTick(ServerPlayer player, Buff buff) {
        switch (buff.tickKind) {
            case REGEN -> {
                if (serverTicks % REGEN_PERIOD == 0 && player.getHealth() < player.getMaxHealth()) {
                    player.heal(REGEN_AMOUNT);
                }
            }
            case SATURATION -> {
                if (serverTicks % SATURATION_PERIOD == 0) {
                    FoodData food = player.getFoodData();
                    food.setFoodLevel(20);
                    if (food.getSaturationLevel() < 6.0f) {
                        food.setSaturation(6.0f);
                    }
                }
            }
            case NONE -> {}
        }
    }

    /** Fills the absorption shield up to the buff's raised {@code MAX_ABSORPTION} on receipt. */
    private static void grantAbsorption(ServerPlayer player) {
        float maxAbsorption = (float) player.getAttributeValue(Attributes.MAX_ABSORPTION);
        if (player.getAbsorptionAmount() < maxAbsorption) {
            player.setAbsorptionAmount(maxAbsorption);
        }
    }
}
