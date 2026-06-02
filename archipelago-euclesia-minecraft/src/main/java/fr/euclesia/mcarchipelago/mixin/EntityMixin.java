package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.DimensionLockService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces the Archipelago dimension gate: a player can't travel through a portal into a dimension
 * whose {@code Dimension Unlock} item hasn't been received yet. {@code canTeleport(from, to)} is the
 * destination-aware check the portal logic consults, so refusing here aborts the teleport and leaves
 * the player in the portal. Only restrictions are added on top of vanilla's own {@code false} cases
 * (e.g. the End→Overworld credits gate), never relaxed.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "canTeleport", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$gateDimensionTravel(Level from, Level to,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(((Object) this) instanceof Player)) {
            return; // vanilla already blocking, or not a player-driven teleport
        }
        String destination = to.dimension().identifier().toString();
        if (DimensionLockService.isDimensionEntryBlocked(destination)) {
            cir.setReturnValue(false);
        }
    }
}
