package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.content.AEMEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Effects for the trap items, dispatched by the {@code slot_data} effect key (see
 * {@code minecraft/filler.py}) from {@link FillerTrapService}. Server thread only. Effects are
 * deliberately nasty-but-survivable; meme-named traps map to the closest sensible disruption.
 */
public final class TrapEffects {
    private static final int TNT_COUNT = 3;
    /** TNT fuse in ticks (half the vanilla 80-tick default). */
    private static final int TNT_FUSE = 40;
    /** Number of mobs in the {@code paris_games_week} crowd, spread in a ring around the victim. */
    private static final int CROWD_COUNT = 10;
    private static final double CROWD_RADIUS = 3.0;
    /** Duration (ticks) of the effect-based traps. */
    private static final int EFFECT_TICKS = 15 * 20;
    /** Mobs the {@code paris_games_week} crowd is drawn from (drop-less, advancement-less trap mobs). */
    private static final List<EntityType<? extends Mob>> CROWD_POOL =
            List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER);

    private TrapEffects() {}

    public static void run(String key, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        switch (key) {
            case "primed_tnt" -> primedTnt(player, level);
            case "aww_man" -> awwMan(player, level);
            case "inventory_shuffle" -> inventoryShuffle(player);
            case "slippery_fingers" -> slipperyFingers(player);
            case "thimble" -> TrapPlatformService.mlg(player, level);
            case "paris_games_week" -> parisGamesWeek(player, level);
            case "item_fear" -> itemFear(player, level);
            default -> AEM.LOGGER.warn("Unknown trap effect '{}'", key);
        }
    }

    /** Drops a few lit TNT on the player's head. */
    private static void primedTnt(ServerPlayer player, ServerLevel level) {
        for (int i = 0; i < TNT_COUNT; i++) {
            PrimedTnt tnt = new PrimedTnt(level, player.getX(), player.getY(), player.getZ(), null);
            tnt.setFuse(TNT_FUSE);
            level.addFreshEntity(tnt);
        }
    }

    /** Conjures a primed creeper right next to the player (it bypasses the spawn lock and drops nothing). */
    private static void awwMan(ServerPlayer player, ServerLevel level) {
        BlockPos pos = player.blockPosition().east();
        TrapMobService.spawn(EntityType.CREEPER, level, pos, Creeper::ignite);
        level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO,
                SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    /** Randomly permutes the player's main inventory, then swaps the hotbar with the first storage row. */
    private static void inventoryShuffle(ServerPlayer player) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        RandomSource rng = player.level().getRandom();
        for (int i = items.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            swap(items, i, j);
        }
        // Then guarantee the hotbar (0-8) is jumbled with the first storage row (9-17).
        for (int i = 0; i < 9; i++) {
            swap(items, i, i + 9);
        }
        player.containerMenu.sendAllDataToRemote();
    }

    /** Butterfingers: for a spell the player fumbles whatever they're holding (see {@link AEMEffects}). */
    private static void slipperyFingers(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(AEMEffects.BUTTERFINGERS, EFFECT_TICKS, 0));
    }

    /** The crowded-expo experience: box the player in with a ring of inert (drop-less) trap mobs. */
    private static void parisGamesWeek(ServerPlayer player, ServerLevel level) {
        BlockPos origin = player.blockPosition();
        for (int i = 0; i < CROWD_COUNT; i++) {
            double angle = 2.0 * Math.PI * i / CROWD_COUNT;
            BlockPos pos = origin.offset(
                    (int) Math.round(Math.cos(angle) * CROWD_RADIUS),
                    0,
                    (int) Math.round(Math.sin(angle) * CROWD_RADIUS));
            TrapMobService.spawn(CROWD_POOL.get(i % CROWD_POOL.size()), level, pos, null);
        }
    }

    /** "Item Fear": scatters the player's whole inventory and makes the loot flee them for a spell. */
    private static void itemFear(ServerPlayer player, ServerLevel level) {
        player.getInventory().dropAll();
        player.containerMenu.sendAllDataToRemote();
        player.addEffect(new MobEffectInstance(AEMEffects.SKITTISH_LOOT, EFFECT_TICKS, 0));
        level.playSound(null, player.blockPosition(), SoundEvents.ELDER_GUARDIAN_CURSE,
                SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    private static void swap(NonNullList<ItemStack> items, int a, int b) {
        ItemStack tmp = items.get(a);
        items.set(a, items.get(b));
        items.set(b, tmp);
    }
}
