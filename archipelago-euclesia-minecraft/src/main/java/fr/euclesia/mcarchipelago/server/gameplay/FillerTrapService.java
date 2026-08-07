package fr.euclesia.mcarchipelago.server.gameplay;

import com.google.gson.Gson;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData.FillerGrant;
import fr.euclesia.mcarchipelago.server.connect.APWorldPaths;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Applies the one-shot effects of received filler and trap items (filler grants a temporary buff via
 * {@link FillerBuffService}; traps fire a {@link TrapEffects} effect). Definitions come from
 * {@code slot_data} (see {@code minecraft_aem/filler.py}) so the apworld stays the source of truth.
 *
 * <p>Archipelago replays the whole item history on every reconnect (the index-0 resync), so a naive
 * "apply on receipt" would duplicate filler each time. Instead we keep persisted high-water marks in
 * {@code archipelago_received.json} in the world folder: how many received items have already been
 * dealt with. On each item batch (and on join, for items received while offline) only the items past
 * the mark are applied — against the ordered history in
 * {@link fr.euclesia.mcarchipelago.registry.APItemRegistry} — and the mark advances. All work runs on
 * the server thread.
 *
 * <p>Two marks, because filler and traps want opposite answers on a shared server.
 *
 * <p><b>Filler is per player.</b> The slot receives one Speed Boost, but the run is being played by
 * several people, and handing it to whoever happened to be first in the player list means everyone
 * else watches a teammate collect the reward. Same reasoning that gives every player their own BACAP
 * reward for a shared advancement: each player carries their own mark and collects every filler
 * exactly once, including the ones that arrived while they were offline.
 *
 * <p><b>Traps are one player.</b> Applying a punishment to everybody multiplies it by the player
 * count, which is not what the trap was priced at — the same call DeathLink makes when it takes a
 * single victim rather than wiping the server. So traps keep a world-scoped mark and fire once, at a
 * randomly chosen online player.
 */
public final class FillerTrapService {
    private static final String FILE_NAME = "archipelago_received.json";
    private static final Gson GSON = new Gson();
    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();

    /**
     * On-disk persistence. {@code appliedItemCount} is the pre-split single mark, kept only so an
     * existing world migrates without replaying its whole filler history at everyone.
     */
    private static final class Progress {
        int appliedItemCount;                  // legacy; seeds both marks below on first load
        int appliedTrapCount = -1;             // world-scoped: traps fire once for the server
        Map<String, Integer> appliedByPlayer;  // player uuid -> filler items already collected
    }

    private FillerTrapService() {}

    /** Applies pending filler to every online player, and pending traps once. */
    public static void applyPendingToAll(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            applyPending(player);
        }
        applyPendingTraps(server);
    }

    /** Applies every filler item this player has not yet collected. */
    public static void applyPending(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return;
        }
        List<Long> order = AEM.ARCHIPELAGO.client().registries().apItems().receivedOrder();
        Progress progress = read(server);
        String key = player.getUUID().toString();
        int applied = Math.min(
                progress.appliedByPlayer.getOrDefault(key, progress.appliedItemCount), order.size());
        if (applied >= order.size()) {
            return;
        }
        APSlotData slot = AEM.ARCHIPELAGO.client().state().parsedSlotData();
        for (int i = applied; i < order.size(); i++) {
            applyFiller(player, slot, order.get(i));
        }
        progress.appliedByPlayer.put(key, order.size());
        write(server, progress);
    }

    /** Fires every trap not yet fired, each at one randomly chosen online player. */
    private static void applyPendingTraps(MinecraftServer server) {
        List<Long> order = AEM.ARCHIPELAGO.client().registries().apItems().receivedOrder();
        Progress progress = read(server);
        int applied = Math.min(progress.appliedTrapCount, order.size());
        if (applied >= order.size()) {
            return;
        }
        APSlotData slot = AEM.ARCHIPELAGO.client().state().parsedSlotData();
        for (int i = applied; i < order.size(); i++) {
            String trap = slot.trapItems().get(order.get(i));
            if (trap == null) {
                continue;
            }
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                // Nobody to spring it on. Leave the mark where it is so the trap still lands when
                // somebody logs back in, rather than being quietly swallowed by an empty server.
                progress.appliedTrapCount = i;
                write(server, progress);
                return;
            }
            TrapEffects.run(trap, players.get(RANDOM.nextInt(players.size())));
        }
        progress.appliedTrapCount = order.size();
        write(server, progress);
    }

    private static void applyFiller(ServerPlayer player, APSlotData slot, long itemId) {
        FillerGrant grant = slot.fillerItems().get(itemId);
        if (grant == null) {
            return; // not filler: a trap, or an item with no one-shot effect
        }
        if (grant.isItem()) {
            FillerItemService.give(player, grant.item(), grant.count());
        } else if (grant.isBuff()) {
            FillerBuffService.applyBuff(player, grant.buff(), grant.seconds());
        }
    }

    private static Progress read(MinecraftServer server) {
        Path file = APWorldPaths.readPath(server, FILE_NAME);
        Progress progress = null;
        if (Files.exists(file)) {
            try {
                progress = GSON.fromJson(Files.readString(file), Progress.class);
            } catch (Exception exception) {
                AEM.LOGGER.warn("Failed to read {}", FILE_NAME, exception);
            }
        }
        if (progress == null) {
            progress = new Progress();
        }
        progress.appliedItemCount = Math.max(0, progress.appliedItemCount);
        if (progress.appliedByPlayer == null) {
            progress.appliedByPlayer = new HashMap<>();
        }
        if (progress.appliedTrapCount < 0) {
            // Migrating a world written before the split: everything up to the old mark has already
            // been dealt with, so start both marks there rather than replaying the run's entire
            // filler history at every player the moment they next log in.
            progress.appliedTrapCount = progress.appliedItemCount;
        }
        return progress;
    }

    private static void write(MinecraftServer server, Progress progress) {
        try {
            Files.writeString(APWorldPaths.writePath(server, FILE_NAME), GSON.toJson(progress));
        } catch (IOException exception) {
            AEM.LOGGER.warn("Failed to write {}", FILE_NAME, exception);
        }
    }
}
