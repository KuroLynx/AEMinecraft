package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.KnowledgeLockService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks opening a brewing stand until {@code Knowledge: Brewing} is received. Consuming the
 * interaction at the head of {@code useWithoutItem} prevents the menu from ever opening.
 */
@Mixin(BrewingStandBlock.class)
public abstract class BrewingStandBlockMixin {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$gateBrewing(BlockState state, Level level, BlockPos pos,
                                                  Player player, BlockHitResult hit,
                                                  CallbackInfoReturnable<InteractionResult> cir) {
        if (KnowledgeLockService.isStationUseBlocked("minecraft:brewing_stand")) {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
