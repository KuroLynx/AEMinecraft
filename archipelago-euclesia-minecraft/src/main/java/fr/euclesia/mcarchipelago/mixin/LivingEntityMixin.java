package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.KeepInventoryService;
import fr.euclesia.mcarchipelago.server.gameplay.TrapMobService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps trap mobs inert: a mob spawned by a trap item ({@link TrapMobService}) drops no death loot, so
 * killing it can't be farmed for resources. Experience is suppressed at spawn and kill credit in
 * {@code ServerPlayerMixin}; this covers the item loot table.
 *
 * <p>It is also where a dying player's keep_inventory share is set aside: this runs only on a real
 * death, after a totem has had its chance, and before the inventory and XP drop.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void archipelago_euclesia$beforeDeathLoot(ServerLevel level, DamageSource source, CallbackInfo ci) {
        if (TrapMobService.isTrapMob((Entity) (Object) this)) {
            ci.cancel();
        } else if ((Object) this instanceof ServerPlayer player) {
            KeepInventoryService.onDeath(player);
        }
    }
}
