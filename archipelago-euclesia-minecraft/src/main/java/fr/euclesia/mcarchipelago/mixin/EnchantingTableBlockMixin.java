package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.KnowledgeLockService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks opening an enchanting table until {@code Knowledge: Enchanting} is received. Consuming the
 * interaction at the head of {@code useWithoutItem} prevents the menu from ever opening.
 */
@Mixin(EnchantingTableBlock.class)
public abstract class EnchantingTableBlockMixin {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$gateEnchanting(BlockState state, Level level, BlockPos pos,
                                                     Player player, BlockHitResult hit,
                                                     CallbackInfoReturnable<InteractionResult> cir) {
        if (KnowledgeLockService.isStationUseBlocked("minecraft:enchanting_table")) {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
