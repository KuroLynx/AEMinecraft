package fr.euclesia.mcarchipelago.server.gameplay;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks the USE of any station or container whose Knowledge hasn't been received — the right-click
 * half of a station/container gate (crafting/picking the block up is the other half, handled by
 * {@link MaterialLockService} through the tool locks). Covers blocks found in the world, not just ones
 * you built, so a village furnace is as inert as one you'd craft.
 *
 * <p>One {@link UseBlockCallback} handler rather than a mixin per block: the gate list is data (it comes
 * from {@code station_knowledge_locks}, generated off the dumped block registry), so it can name any of
 * ~26 vanilla blocks plus whatever a mod adds, and there is no shared block class to inject into. This
 * replaces the hand-written EnchantingTableBlock/BrewingStandBlock mixins.
 *
 * <p>Sneaking is left alone: right-clicking a block while sneaking is how you place something against
 * it, and vanilla skips the block's own use for exactly that reason. Cancelling it would stop players
 * building against a locked chest, which the gate has no business doing.
 */
public final class KnowledgeUseGate {
    private KnowledgeUseGate() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || player.isSecondaryUseActive()) {
                return InteractionResult.PASS;
            }
            BlockState state = level.getBlockState(hit.getBlockPos());
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            Component reason = KnowledgeLockService.stationBlockReason(blockId);
            if (reason == null) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                LockFeedback.notify(serverPlayer, reason);
            }
            // CONSUME rather than SUCCESS: the interaction is swallowed without the arm swing, matching
            // how the old per-block mixins refused it.
            return InteractionResult.CONSUME;
        });
    }
}
