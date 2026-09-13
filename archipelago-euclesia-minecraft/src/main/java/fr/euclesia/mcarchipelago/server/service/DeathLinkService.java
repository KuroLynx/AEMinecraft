package fr.euclesia.mcarchipelago.server.service;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.archipelago.DeathLinkPreference;
import fr.euclesia.mcarchipelago.protocol.APBounceType;
import fr.euclesia.mcarchipelago.protocol.APJson;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * DeathLink: a death here is a death everywhere, and everywhere is a death here.
 *
 * <p>Two things have to be right on a server, and both were wrong.
 *
 * <p><b>Archipelago echoes a Bounce back to the slot that sent it.</b> Bounces go to every client
 * carrying the tag, and we carry it — so a player's own death came straight back as an incoming link
 * and killed somebody else. Each payload is now stamped with the slot that sent it, and a bounce
 * carrying our own stamp is dropped. Other worlds are unaffected: their deaths carry no stamp of
 * ours, so they land normally.
 *
 * <p><b>A kill is not instantaneous.</b> {@code kill()} schedules the death; the AFTER_DEATH event
 * fires afterwards. A boolean flipped around the {@code kill()} call is therefore already cleared by
 * the time the death actually arrives, so the victim's death sent a bounce of its own, which echoed
 * back, which killed the next player — a chain that walked through the entire server one player at a
 * time. Suppression is now held per player, keyed by uuid, and released only when that player's
 * death actually comes through.
 */
public final class DeathLinkService {
    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();

    /** Our own key in the room: team and slot together identify one world uniquely. */
    private static final String ORIGIN_KEY = "aem_origin";

    /**
     * Players we are in the middle of killing because of an incoming link, whose imminent death must
     * not send a bounce of its own. Held until the death arrives rather than for the duration of the
     * kill call, because those are not the same moment.
     *
     * <p>The value is the victim's insomnia counter as it stood before the kill, so {@link
     * #onLocalPlayerDeath} can put it back — see there for why.
     */
    private static final Map<UUID, Integer> linkKilled = new ConcurrentHashMap<>();

    private DeathLinkService() {}

    public static void onLocalPlayerDeath(ServerPlayer player, DamageSource source) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            return;
        }
        // A death we caused is not news. The mark is consumed here, where the death finally lands.
        Integer insomniaBeforeLink = linkKilled.remove(player.getUUID());
        if (insomniaBeforeLink != null) {
            // Give the insomnia counter back. ServerPlayer#die resets TIME_SINCE_REST, and phantoms
            // need it past 72000 ticks — an hour of real time. A death somebody else died is not rest,
            // so letting it reset the counter meant that on a busy multiworld with DeathLink on the
            // timer restarted often enough that phantoms never spawned at all (and Two Birds, One
            // Arrow with them). Deaths the player earns themselves still reset it, as vanilla does.
            player.getStats().setValue(player, insomniaStat(), insomniaBeforeLink);
            AEMDebug.log("deathLink.local suppressed for {} (we killed them); insomnia restored to {}",
                    player.getGameProfile().name(), insomniaBeforeLink);
            return;
        }
        if (DeathLinkPreference.enabled()) {
            AEMDebug.log("deathLink.local sending bounce for {}", player.getGameProfile().name());
            AEM.ARCHIPELAGO.gateway().bounce(APBounceType.DEATH_LINK, createPayload(player, source));
        }
    }

    /** Handles an incoming DeathLink bounce. {@code data} is the bounce's payload object. */
    public static void applyRemote(JsonObject data) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        String origin = APJson.getString(data, ORIGIN_KEY, "");
        if (!origin.isEmpty() && origin.equals(localOrigin())) {
            AEMDebug.log("deathLink.remote ignoring our own echoed bounce (origin={})", origin);
            return;
        }

        String source = APJson.getString(data, "source", "");
        String cause = APJson.getString(data, "cause", "");
        Component message = Component.translatable("message.aem.deathlink", describe(source, cause));

        server.execute(() -> {
            // ONE player per incoming link, not the whole server. Killing everyone turns a single
            // remote death into a server-wide wipe, wildly out of proportion to what caused it.
            List<ServerPlayer> living = server.getPlayerList().getPlayers().stream()
                    .filter(ServerPlayer::isAlive)
                    .toList();
            if (living.isEmpty()) {
                AEMDebug.log("deathLink.remote no living player online; dropped");
                return;
            }
            ServerPlayer victim = living.get(RANDOM.nextInt(living.size()));
            AEMDebug.log("deathLink.remote killing {} (1 of {} living)",
                    victim.getGameProfile().name(), living.size());

            // Marked BEFORE the kill and left marked: onLocalPlayerDeath consumes it whenever the
            // death event actually lands, which is not necessarily within this call.
            linkKilled.put(victim.getUUID(), victim.getStats().getValue(insomniaStat()));
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.aem.deathlink.victim",
                            victim.getDisplayName(), message), false);
            victim.kill(victim.level());
        });
    }

    /** Ticks since the player last slept — the counter {@code PhantomSpawner} reads. */
    private static Stat<?> insomniaStat() {
        return Stats.CUSTOM.get(Stats.TIME_SINCE_REST);
    }

    /** Drops a pending suppression, so a disconnect mid-kill cannot leave a player permanently muted. */
    public static void onPlayerDisconnect(ServerPlayer player) {
        linkKilled.remove(player.getUUID());
    }

    /**
     * Builds the detail half of the DeathLink message. Archipelago's {@code cause} field is already a
     * full sentence (e.g. "Steve was slain by a Zombie") when present, so it is shown verbatim;
     * otherwise we fall back to naming the source slot, and only then to a generic phrase.
     */
    private static Component describe(String source, String cause) {
        if (cause != null && !cause.isBlank()) {
            return Component.literal(cause);
        }
        String name = source != null && !source.isBlank() ? source : null;
        return name != null
                ? Component.translatable("message.aem.deathlink.unknown", name)
                : Component.translatable("message.aem.deathlink.anonymous");
    }

    private static String localOrigin() {
        return AEM.ARCHIPELAGO.client().state().team() + ":" + AEM.ARCHIPELAGO.client().state().slot();
    }

    private static JsonObject createPayload(ServerPlayer player, DamageSource source) {
        JsonObject data = new JsonObject();
        data.addProperty("time", Instant.now().toEpochMilli() / 1000.0);
        data.addProperty("source", player.getGameProfile().name());
        // The exact DamageSource MC just used to kill the player yields the real, fully-rendered
        // death sentence ("Steve was slain by Zombie", "Steve fell from a high place", …). The
        // CombatTracker fallback used previously degraded to the generic "Steve died" when the
        // tracker couldn't attribute the kill at AFTER_DEATH time.
        data.addProperty("cause", source.getLocalizedDeathMessage(player).getString());
        // Who sent it, so we can recognise our own echo. Unknown keys are ignored by other clients.
        data.addProperty(ORIGIN_KEY, localOrigin());
        return data;
    }
}
