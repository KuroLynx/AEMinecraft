package fr.euclesia.mcarchipelago.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.euclesia.mcarchipelago.server.gameplay.MaterialLockService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.GiveCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Extends the Archipelago material/tool lock to the {@code /give} command. When the {@code given} route
 * of the {@code ItemGateBehavior} option is gated, {@code /give}-ing a still-locked item is refused with
 * the usual red "requires ..." reason instead of quietly bypassing the economy. If that route is left
 * open (the default), this does nothing and the command runs normally.
 */
@Mixin(GiveCommand.class)
public abstract class GiveCommandMixin {
    @Inject(method = "giveItem", at = @At("HEAD"), cancellable = true)
    private static void archipelago_euclesia$blockLockedGive(
            CommandSourceStack source,
            ItemInput item,
            Collection<ServerPlayer> targets,
            int count,
            CallbackInfoReturnable<Integer> cir) throws CommandSyntaxException {
        // A one-count probe is enough to identify the item for the lock check; the real stacks are
        // built later in the (now cancelled) method body.
        ItemStack probe = item.createItemStack(1);
        Component reason = MaterialLockService.blockReason(probe, MaterialLockService.Channel.GIVEN);
        if (reason != null) {
            source.sendFailure(reason);
            cir.setReturnValue(0);
        }
    }
}
