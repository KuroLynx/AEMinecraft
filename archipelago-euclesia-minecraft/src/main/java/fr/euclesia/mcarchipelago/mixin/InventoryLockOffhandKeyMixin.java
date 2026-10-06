package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.InventoryLockService;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The swap-offhand key never touches a {@code Slot}: the server swaps the two hands directly, so
 * {@link InventoryLockArmorSlotMixin} never sees it and a locked offhand (or a locked selected hotbar
 * slot) took the item anyway. Refuse the swap when either side would receive an item into a locked
 * slot. Checked on the server thread only — the first call arrives on the network thread and is
 * rescheduled.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class InventoryLockOffhandKeyMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$blockLockedOffhandSwap(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                || !player.level().getServer().isSameThread()) {
            return;
        }
        Inventory inventory = player.getInventory();
        boolean intoOffhand = !player.getMainHandItem().isEmpty() && InventoryLockService.isLocked(Inventory.SLOT_OFFHAND);
        boolean intoHotbar = !player.getOffhandItem().isEmpty() && InventoryLockService.isLocked(inventory.getSelectedSlot());
        if (intoOffhand || intoHotbar) {
            ci.cancel();
        }
    }
}
