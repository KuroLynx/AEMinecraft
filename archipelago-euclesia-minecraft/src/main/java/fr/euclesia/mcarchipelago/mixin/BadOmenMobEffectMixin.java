package fr.euclesia.mcarchipelago.mixin;

import fr.euclesia.mcarchipelago.server.gameplay.MobSpawnLockService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the Bad Omen -> Raid Omen conversion back while any raid mob is still spawn-locked.
 *
 * <p>{@code applyEffectTick} is the single point where Bad Omen becomes a raid: standing in a
 * village with the effect swaps it for Raid Omen and records the raid position, and Raid Omen is
 * what actually starts the siege. Cancelling here therefore cancels the raid at its source, rather
 * than letting one start and then fail.
 *
 * <p>It has to be stopped here because a raid whose roster is incomplete cannot be won. A wave only
 * clears once its raiders are dead, and a locked raider never spawns (its {@code addEntity} is
 * refused by the mob-lock), so the wave sits at a count it can never reach — the raid bar hangs
 * over the village forever and the Bad Omen is spent for nothing.
 *
 * <p>Returning {@code true} is what keeps this recoverable: vanilla returns {@code false} only on
 * the conversion path, which is how the effect signals it has been consumed. Cancelling with
 * {@code true} leaves Bad Omen on the player, ticking and unspent, so once the missing unlocks
 * arrive the very next tick in a village converts it and the raid starts as usual. The player loses
 * nothing but time.
 *
 * <p>The hold isn't silent: where vanilla would have started the raid (a player, not spectating, in a
 * village, off peaceful), the player is told once per Bad Omen which raid mobs are still locked.
 */
// Targeted by name: BadOmenMobEffect is package-private, so it cannot be imported from here.
@Mixin(targets = "net.minecraft.world.effect.BadOmenMobEffect")
public abstract class BadOmenMobEffectMixin {
    // The Bad Omen each player was last told about; drinking another bottle makes a new instance.
    private static final Map<UUID, MobEffectInstance> aem$warned = new ConcurrentHashMap<>();

    @Inject(method = "applyEffectTick", at = @At("HEAD"), cancellable = true)
    private void aem$holdRaidWhileRaidersLocked(ServerLevel level, LivingEntity entity, int amplifier,
                                                CallbackInfoReturnable<Boolean> cir) {
        List<String> locked = MobSpawnLockService.lockedRaidMobs();
        if (locked.isEmpty()) {
            return;
        }
        cir.setReturnValue(true);
        if (entity instanceof ServerPlayer player && !player.isSpectator()
                && level.getDifficulty() != Difficulty.PEACEFUL && level.isVillage(player.blockPosition())) {
            MobEffectInstance omen = player.getEffect(MobEffects.BAD_OMEN);
            if (omen != null && aem$warned.put(player.getUUID(), omen) != omen) {
                player.sendSystemMessage(Component.translatable("message.aem.raid.held",
                        ComponentUtils.formatList(locked, id ->
                                BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id)).getDescription()))
                        .withStyle(ChatFormatting.RED));
            }
        }
    }
}
