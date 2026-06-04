package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;

/**
 * Effects for the trap items, dispatched by the {@code slot_data} effect key (see
 * {@code minecraft/filler.py}) from {@link FillerTrapService}. Server thread only. Effects are
 * deliberately nasty-but-survivable; meme-named traps map to the closest sensible disruption.
 */
public final class TrapEffects {
    private static final int TNT_COUNT = 3;
    private static final int ZOMBIE_COUNT = 3;

    private TrapEffects() {}

    public static void run(String key, ServerPlayer player) {
        switch (key) {
            case "primed_tnt" -> primedTnt(player);
            case "aww_man" -> awwMan(player);
            case "inventory_shuffle" -> inventoryShuffle(player);
            case "slippery_fingers" -> slipperyFingers(player);
            case "thimble" -> thimble(player);
            case "paris_games_week" -> parisGamesWeek(player);
            case "cursed_loot" -> cursedLoot(player);
            default -> AEM.LOGGER.warn("Unknown trap effect '{}'", key);
        }
    }

    /** Drops a few lit TNT on the player's head. */
    private static void primedTnt(ServerPlayer player) {
        ServerLevel level = player.level();
        for (int i = 0; i < TNT_COUNT; i++) {
            PrimedTnt tnt = new PrimedTnt(level, player.getX(), player.getY(), player.getZ(), null);
            tnt.setFuse(80);
            level.addFreshEntity(tnt);
        }
    }

    /** Disorientation + a dejected grunt. */
    private static void awwMan(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 200, 0));
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO,
                SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    /** Randomly permutes the player's main inventory slots. */
    private static void inventoryShuffle(ServerPlayer player) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        RandomSource rng = player.level().getRandom();
        for (int i = items.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            ItemStack tmp = items.get(i);
            items.set(i, items.get(j));
            items.set(j, tmp);
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** Fumbles the currently held stack onto the ground (soulbound items are kept by their own mixin). */
    private static void slipperyFingers(ServerPlayer player) {
        if (!player.getInventory().getSelectedItem().isEmpty()) {
            player.drop(true);
        }
    }

    /** Leaves the player on a thimble of health (half a heart) — never heals. */
    private static void thimble(ServerPlayer player) {
        player.setHealth(Math.min(player.getHealth(), 1.0f));
    }

    /** The crowded-expo experience: slow, sluggish and queasy for a while. */
    private static void parisGamesWeek(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 600, 2));
        player.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 600, 1));
        player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 300, 0));
    }

    /** Spawns a small huddle of zombies around the player (blocked mobs simply don't appear). */
    private static void cursedLoot(ServerPlayer player) {
        ServerLevel level = player.level();
        RandomSource rng = level.getRandom();
        BlockPos origin = player.blockPosition();
        for (int i = 0; i < ZOMBIE_COUNT; i++) {
            BlockPos pos = origin.offset(rng.nextInt(5) - 2, 0, rng.nextInt(5) - 2);
            EntityType.ZOMBIE.spawn(level, pos, EntitySpawnReason.EVENT);
        }
        level.playSound(null, origin, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.0f, 1.0f);
    }
}
