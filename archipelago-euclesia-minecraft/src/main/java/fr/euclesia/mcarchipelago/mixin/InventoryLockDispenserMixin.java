package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dispenser fires armor straight onto a player in front of it ({@code EquipmentDispenseItemBehavior}
 * -> {@code setItemSlot}), past every slot the other lock mixins guard. That behaviour only picks an
 * entity that {@code canDispenserEquipIntoSlot}, so refusing a locked slot here makes it skip the
 * player and dispense the item as usual.
 */
@Mixin(LivingEntity.class)
public abstract class InventoryLockDispenserMixin {
    @Inject(method = "canDispenserEquipIntoSlot", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$blockLockedDispense(EquipmentSlot slot, CallbackInfoReturnable<Boolean> cir) {
        int index = InventoryLockService.indexOf(slot);
        if ((Object) this instanceof Player && index >= 0 && InventoryLockService.isLocked(index)) {
            cir.setReturnValue(false);
        }
    }
}
