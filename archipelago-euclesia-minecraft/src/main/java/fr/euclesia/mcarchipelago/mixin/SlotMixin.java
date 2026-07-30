package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.LockFeedback;
import fr.euclesia.mcarchipelago.server.gameplay.MaterialLockService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends the Archipelago material/tool lock past floor pickup: a gated item can't be taken out of a
 * container GUI, crafting result, or workstation output until it's unlocked. Vanilla checks
 * {@code Slot#mayPickup} for both normal clicks and shift-click quick-moves, so this single chokepoint
 * covers every take. Slots backed by the player's own inventory are left alone — once an item is in
 * your inventory it stays yours; we only gate acquiring it from an external source.
 *
 * <p>Which GUI the take happened in decides which {@code item_gate_behavior} route applies, so a seed can
 * gate hand-crafting without gating chests (or the reverse) — see {@link #archipelago_euclesia$channelFor}.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow
    @Final
    public Container container;

    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "mayPickup", at = @At("RETURN"), cancellable = true)
    private void archipelago_euclesia$blockLockedTake(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || player.level().isClientSide()) {
            return;
        }
        // Taking/moving items already in the player's own inventory is always allowed.
        if (this.container == player.getInventory()) {
            return;
        }
        MaterialLockService.Channel channel = archipelago_euclesia$channelFor(player);
        Component reason = MaterialLockService.blockReason(getItem(), channel);
        if (reason != null) {
            cir.setReturnValue(false);
            if (player instanceof ServerPlayer serverPlayer) {
                LockFeedback.notify(serverPlayer, reason);
            }
        }
    }

    /**
     * Sorts this take into one of the three GUI routes, keyed off the menu it happens in rather than the
     * slot class — the anvil/smithing/grindstone/stonecutter/loom/cartography outputs are anonymous
     * {@link Slot} subclasses with no shared type to test for, while the menu is always identifiable.
     *
     * <ul>
     *   <li>{@code CRAFTING} — a crafting grid: the crafting table and the player's own 2x2 grid both run
     *       on {@link AbstractCraftingMenu}, so its grid and {@link ResultSlot} alike land here. The
     *       {@link ResultSlot} test also stands alone, in case a mod reuses the slot outside that menu.
     *   <li>{@code STATION} — a workstation that makes or transforms items. Includes its input slots, not
     *       just the output: that matches the pre-split behavior, where one {@code crafting} flag gated
     *       every slot of every menu.
     *   <li>{@code CONTAINER} — anything else, which is plain storage (chest, barrel, shulker box, hopper,
     *       dispenser, ender chest, mount inventories), plus GUIs that only hold an item without
     *       transforming it (beacon payment, lectern).
     * </ul>
     */
    private MaterialLockService.Channel archipelago_euclesia$channelFor(Player player) {
        AbstractContainerMenu menu = player.containerMenu;
        // In a mixin `this` is typed as the mixin class, so the instanceof needs the Object detour to
        // see the Slot we are actually injected into.
        Object self = this;
        if (self instanceof ResultSlot || menu instanceof AbstractCraftingMenu) {
            return MaterialLockService.Channel.CRAFTING;
        }
        boolean station = menu instanceof AbstractFurnaceMenu       // furnace, blast furnace, smoker
                || menu instanceof ItemCombinerMenu                 // anvil, smithing table
                || menu instanceof GrindstoneMenu
                || menu instanceof StonecutterMenu
                || menu instanceof LoomMenu
                || menu instanceof CartographyTableMenu
                || menu instanceof BrewingStandMenu
                || menu instanceof EnchantmentMenu
                || menu instanceof MerchantMenu                     // villager / wandering trader
                || menu instanceof CrafterMenu;
        return station ? MaterialLockService.Channel.STATION : MaterialLockService.Channel.CONTAINER;
    }
}
