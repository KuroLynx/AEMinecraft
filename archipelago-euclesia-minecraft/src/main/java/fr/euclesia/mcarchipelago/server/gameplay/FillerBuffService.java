package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.content.FillerBuffEffects;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Grants the temporary buffs handed out by filler items (see {@code minecraft_aem/filler.py} and
 * {@link FillerTrapService}). Each buff is a real, custom {@link MobEffect} registered by
 * {@link FillerBuffEffects} under the {@code aem:} namespace — so the player sees it in the HUD and
 * inventory with an icon and a ticking-down duration, exactly like a potion effect. The effects use
 * <em>custom</em> ids (never a {@code minecraft:} effect), so receiving one can't satisfy a BACAP effect
 * advancement predicate and hand out a free check or desync the logic.
 *
 * <p>Durations <b>stack</b>: each received copy adds its seconds onto the buff's remaining timer. All
 * work runs on the server thread.
 */
public final class FillerBuffService {
    private static final int TPS = 20;

    private FillerBuffService() {}

    /**
     * Grants (or extends) a buff on {@code player} for {@code seconds}. Duration stacks onto whatever is
     * already running. Called from {@link FillerTrapService} when a buff filler item is received.
     */
    public static void applyBuff(ServerPlayer player, String key, int seconds) {
        Holder<MobEffect> effect = FillerBuffEffects.byKey(key);
        if (effect == null) {
            AEM.LOGGER.warn("Unknown filler buff '{}'", key);
            return;
        }
        MobEffectInstance current = player.getEffect(effect);
        int remaining = current == null ? 0 : Math.max(0, current.getDuration());
        int total = remaining + seconds * TPS;
        // ambient=false, visible=false (no particles), showIcon=true (HUD/inventory icon + countdown).
        player.addEffect(new MobEffectInstance(effect, total, 0, false, false, true));
        // Overlay (action bar) note so the player also sees the buff and its remaining seconds at a glance.
        player.sendSystemMessage(Component.translatable("filler.aem.buff.applied",
                Component.translatable("filler.aem.buff." + key), total / TPS), true);
    }
}
