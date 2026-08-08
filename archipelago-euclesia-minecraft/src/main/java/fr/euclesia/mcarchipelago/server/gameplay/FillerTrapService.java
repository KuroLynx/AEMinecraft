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
 * <p>The mark is per player. The slot receives one Speed Boost, but the run is being played by
 * several people, and handing it to whoever happened to be first in the player list means everyone
 * else watches a teammate collect the reward — the same reasoning that gives every player their own
 * BACAP reward for a shared advancement. So each player carries their own mark and gets every filler
 * exactly once, including the ones that arrived while they were offline.
 *
 * <p><b>Traps do not catch up.</b> Filler is a gift and keeps: collect it whenever you next log in.
 * A trap is an event — it happens to the people who are in the world when it lands. Firing a week of
 * banked traps at whoever logs in next punishes them for having been away, and lands as an
 * unsurvivable pile rather than the moment of chaos each one was meant to be. So a catch-up pass
 * ({@link Mode#CATCH_UP}) applies the filler it finds and walks the traps past without firing them;
 * only a live pass ({@link Mode#LIVE}), for players who were actually present, springs them.
 *
 * <p>Live traps still wait out {@link SpawnGraceService}: a trap that went off against a player's
 * arrival shield would be spent for nothing, so it is left pending and fired when their grace ends.
 *
 * <p>(DeathLink is a different thing entirely: a death arriving from ANOTHER world takes one victim
 * rather than wiping the server. These are this slot's own items.)
 */
public final class FillerTrapService {
    private static final String FILE_NAME = "archipelago_received.json";
    private static final Gson GSON = new Gson();

    /**
     * On-disk persistence. The two int fields are older shapes of this file, read but never written
     * again: they seed a player's first mark so an existing world does not replay its whole item
     * history at everyone the moment they next log in.
     */
    private static final class Progress {
        int appliedItemCount;                  // pre-split single mark; seeds a player's first mark
        int appliedTrapCount = -1;             // no longer used; read only so old files stay parseable
        Map<String, Integer> appliedByPlayer;  // player uuid -> items already applied to them
    }

    /** Whether a pass may spring the traps it finds, or only collect the filler. */
    public enum Mode {
        /** The player was here when these arrived: filler and traps both. */
        LIVE,
        /** The player was not: filler only, traps marked as spent without firing. */
        CATCH_UP
    }

    private FillerTrapService() {}

    /** Applies what just arrived to the players who are actually in the world for it. */
    public static void applyPendingToAll(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            applyPending(player, Mode.LIVE);
        }
    }

    /** Applies every filler this player has not yet had, and their traps if {@code mode} is LIVE. */
    public static void applyPending(ServerPlayer player, Mode mode) {
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
            applyOne(player, slot, order.get(i), mode);
        }
        progress.appliedByPlayer.put(key, order.size());
        write(server, progress);
    }

    private static void applyOne(ServerPlayer player, APSlotData slot, long itemId, Mode mode) {
        FillerGrant grant = slot.fillerItems().get(itemId);
        if (grant != null) {
            if (grant.isItem()) {
                FillerItemService.give(player, grant.item(), grant.count());
            } else if (grant.isBuff()) {
                FillerBuffService.applyBuff(player, grant.buff(), grant.seconds());
            }
            return;
        }
        String trap = slot.trapItems().get(itemId);
        if (trap != null && mode == Mode.LIVE) {
            // Queued rather than fired: traps are spaced out, and none goes off during the arrival
            // grace (see TrapScheduler).
            TrapScheduler.submit(player, trap);
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
