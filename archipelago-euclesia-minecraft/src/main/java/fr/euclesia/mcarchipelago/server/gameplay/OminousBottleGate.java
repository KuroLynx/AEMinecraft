package fr.euclesia.mcarchipelago.server.gameplay;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
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
 * the bottle kept: outside both structures the player is told where to drink it, inside one they are
 * told which mobs it still needs. A locked structure doesn't count: {@code StructureManagerMixin} hides it from the
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
                    || !player.getItemInHand(hand).is(Items.OMINOUS_BOTTLE)) {
                return InteractionResult.PASS;
            }
            Component refusal = refusal(serverLevel, player.blockPosition());
            if (refusal == null) {
                return InteractionResult.PASS;
            }
            LockFeedback.notify(serverPlayer, refusal);
            return InteractionResult.FAIL;
        });
    }

    /** Why the bottle can't be drunk here, or {@code null} when it can. */
    private static Component refusal(ServerLevel level, BlockPos pos) {
        boolean inVillage = level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE).isValid();
        boolean inTrials = level.structureManager()
                .getStructureWithPieceAt(pos, holder -> holder.is(TRIAL_CHAMBERS)).isValid();
        List<String> raidMissing = MobSpawnLockService.lockedRaidMobs();
        boolean trialMob = TRIAL_MOB_IDS.stream().anyMatch(id -> !MobSpawnLockService.isMobLocked(id));
        if ((inVillage && raidMissing.isEmpty()) || (inTrials && trialMob)) {
            return null;
        }
        if (inVillage) {
            return Component.translatable("message.aem.ominous_bottle.missing_raid",
                    ComponentUtils.formatList(raidMissing, OminousBottleGate::mobName)).withStyle(ChatFormatting.RED);
        }
        if (inTrials) {
            // Any one of them is enough, so they are listed as alternatives.
            return Component.translatable("message.aem.ominous_bottle.missing_trial",
                    ComponentUtils.formatList(TRIAL_MOB_IDS, Component.literal(" or "), OminousBottleGate::mobName))
                    .withStyle(ChatFormatting.RED);
        }
        return Component.translatable("message.aem.ominous_bottle.where").withStyle(ChatFormatting.GOLD);
    }

    private static Component mobName(String id) {
        return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id)).getDescription();
    }
}
