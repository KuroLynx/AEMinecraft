package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.content.BiomeFinderItem;
import fr.euclesia.mcarchipelago.server.gameplay.TrapMobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the soulbound Biome Finder undroppable. Every player-initiated toss — the Q drop key and
 * clicking a carried stack outside the inventory — funnels through
 * {@code ServerPlayer#drop(ItemStack, boolean, boolean)}, which spawns the world {@link ItemEntity}.
 * For a finder stack we instead put it straight back (into the inventory, or onto the cursor if the
 * inventory is full) and return {@code null}; callers discard the returned entity, so no item drops
 * and none is lost. Death drops are handled separately (the finder is stripped in ALLOW_DEATH).
 *
 * <p>Also denies kill credit for trap mobs ({@link TrapMobService}) in {@code awardKillScore}, so killing
 * a trap-conjured creeper or crowd awards no score or kill stat. The matching advancement suppression
 * (e.g. Monster Hunter / the Adventure root) lives in {@code KilledTriggerMixin}.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(
            method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"),
            cancellable = true)
    private void archipelago_euclesia$keepBiomeFinder(ItemStack stack, boolean throwRandomly,
                                                      boolean retainOwnership,
                                                      CallbackInfoReturnable<ItemEntity> cir) {
        if (!BiomeFinderItem.isFinder(stack)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (!player.getInventory().add(stack)) {
            player.containerMenu.setCarried(stack);
        }
        cir.setReturnValue(null);
    }

    @Inject(method = "awardKillScore", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$noTrapMobKillCredit(Entity killed, DamageSource source, CallbackInfo ci) {
        if (TrapMobService.isTrapMob(killed)) {
            ci.cancel();
        }
    }
}
