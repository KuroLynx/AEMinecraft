package fr.euclesia.mcarchipelago.server.gameplay;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * An ominous bottle can only be drunk where its Bad Omen has a use: inside a village with every raid
 * mob unlocked (a raid), or inside the Trial Chambers with one of their spawner mobs unlocked (an
 * ominous trial). Anywhere else the Bad Omen would be spent on nothing, so the drink is refused and
 * the bottle kept. A locked structure doesn't count: {@code StructureManagerMixin} hides it from the
 * "inside" lookup. Logic asks for the same ({@code can_use_ominous_bottle} in the apworld).
 */
public final class OminousBottleGate {
    private static final Identifier TRIAL_CHAMBERS = Identifier.withDefaultNamespace("trial_chambers");

    /**
     * What the trial spawners can spawn ({@code data/minecraft/trial_spawner/*}, the same on 26.1.2,
     * 26.2 and 26.3). Hard-coded like the raid roster; mirrors {@code MOBS_TRIAL} in the apworld.
     */
    private static final List<String> TRIAL_MOB_IDS = List.of(
            "minecraft:bogged", "minecraft:breeze", "minecraft:cave_spider", "minecraft:husk",
            "minecraft:silverfish", "minecraft:skeleton", "minecraft:slime", "minecraft:spider",
            "minecraft:stray", "minecraft:zombie");

    private OminousBottleGate() {}

    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)
                    || !player.getItemInHand(hand).is(Items.OMINOUS_BOTTLE) || hasUse(serverLevel, player.blockPosition())) {
                return InteractionResult.PASS;
            }
            LockFeedback.notify(serverPlayer, Component.translatable("message.aem.ominous_bottle.no_use"));
            return InteractionResult.FAIL;
        });
    }

    private static boolean hasUse(ServerLevel level, BlockPos pos) {
        boolean raid = MobSpawnLockService.lockedRaidMobs().isEmpty()
                && level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE).isValid();
        return raid || (TRIAL_MOB_IDS.stream().anyMatch(id -> !MobSpawnLockService.isMobLocked(id))
                && level.structureManager().getStructureWithPieceAt(pos, holder -> holder.is(TRIAL_CHAMBERS)).isValid());
    }
}
