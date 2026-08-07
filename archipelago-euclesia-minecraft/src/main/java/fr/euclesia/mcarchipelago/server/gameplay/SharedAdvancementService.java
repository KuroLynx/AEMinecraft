package fr.euclesia.mcarchipelago.server.gameplay;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.AEMDebug;
import fr.euclesia.mcarchipelago.server.connect.APWorldPaths;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One shared advancement book for the whole server.
 *
 * <p>An Archipelago slot is a single player's game, but a server is several people playing it
 * together: if one of them opens the Nether, the run has opened the Nether. So when anybody earns an
 * advancement, everybody earns it. That keeps every player's tab showing the true state of the run
 * rather than their personal share of it, and it is what makes checks make sense — a location is
 * sent once, by whoever got there first, and the rest of the server is not left looking at a red
 * tile for something the run has already done.
 *
 * <p>Crucially the others are granted the advancement for real, through the same
 * {@code PlayerAdvancements.award} vanilla uses, rather than being handed a cosmetic copy. That is
 * what makes BACAP's rewards work: its reward chests and trophies hang off normal advancement
 * completion, so each player's own completion pays them their own reward, exactly as if they had
 * done it themselves. Anything else would have one player collect the loot for everyone.
 *
 * <p>The set is persisted per world, so a player who logs in tomorrow — or the first player back
 * after a restart — catches up on everything the run has done rather than starting from the empty
 * book their player file remembers.
 */
public final class SharedAdvancementService {
    private static final String FILE_NAME = "shared_advancements.json";
    private static final Gson GSON = new Gson();

    /** Every advancement the RUN has completed, by id, in completion order. */
    private static final Set<String> completed = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * Set while we are propagating one completion outward. Awarding an advancement to another player
     * makes their own {@code award} fire, which lands back here — without this the first completion
     * would ripple round the player list forever. It also keeps the Archipelago check to one send:
     * the first player through the door reports the location, the copies do not.
     */
    private static boolean propagating;

    private static boolean dirty;

    private SharedAdvancementService() {}

    /** Whether a completion arriving now is one we caused (so: do not re-broadcast, do not re-check). */
    public static boolean isPropagating() {
        return propagating;
    }

    /**
     * Record a completion and give it to everyone else. Returns whether this was NEW to the run —
     * the caller uses that to decide whether to send the Archipelago check, so a location is
     * reported exactly once no matter how many players end up holding the advancement.
     */
    public static boolean onCompleted(ServerPlayer source, String advancementId) {
        if (propagating) {
            return false; // a copy we just handed out, not a fresh completion
        }
        if (!completed.add(advancementId)) {
            return false; // the run already had this; another player simply caught up
        }
        dirty = true;
        save();

        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return true;
        }
        AdvancementHolder holder = find(server, advancementId);
        if (holder == null) {
            return true;
        }
        AEMDebug.log("sharedAdvancement '{}' earned by {} -> sharing", advancementId,
                source != null ? source.getGameProfile().name() : "?");
        propagating = true;
        try {
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other != source) {
                    grant(other, holder);
                }
            }
        } finally {
            propagating = false;
        }
        return true;
    }

    /**
     * Bring a joining player up to the run's current state. Runs inside the propagation guard, so a
     * player catching up on two hundred advancements does not fire two hundred Archipelago checks
     * for locations the run sent long ago.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || completed.isEmpty()) {
            return;
        }
        List<String> snapshot;
        synchronized (completed) {
            snapshot = List.copyOf(completed);
        }
        propagating = true;
        try {
            int granted = 0;
            for (String id : snapshot) {
                AdvancementHolder holder = find(server, id);
                if (holder != null && grant(player, holder)) {
                    granted++;
                }
            }
            AEMDebug.log("sharedAdvancement caught {} up on {} advancement(s)",
                    player.getGameProfile().name(), granted);
        } finally {
            propagating = false;
        }
    }

    /**
     * Award every criterion the player is still missing. Awarding by name makes this idempotent, so
     * re-running it on every join costs nothing and cannot double-grant a reward.
     *
     * @return whether anything was actually awarded
     */
    private static boolean grant(ServerPlayer player, AdvancementHolder holder) {
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        if (progress.isDone()) {
            return false;
        }
        boolean awarded = false;
        for (String criterion : holder.value().criteria().keySet()) {
            CriterionProgress state = progress.getCriterion(criterion);
            if (state == null || !state.isDone()) {
                awarded |= player.getAdvancements().award(holder, criterion);
            }
        }
        return awarded;
    }

    private static AdvancementHolder find(MinecraftServer server, String advancementId) {
        Identifier id = Identifier.tryParse(advancementId);
        return id != null ? server.getAdvancements().get(id) : null;
    }

    // -- persistence ---------------------------------------------------------

    /** Loads the run's advancement set for this world. Called as the server starts. */
    public static void load(MinecraftServer server) {
        completed.clear();
        dirty = false;
        Path path = APWorldPaths.dir(server).resolve(FILE_NAME);
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<String> stored = GSON.fromJson(Files.readString(path),
                    new TypeToken<List<String>>() {}.getType());
            if (stored != null) {
                completed.addAll(stored);
            }
            AEM.LOGGER.info("Loaded {} shared advancement(s) for this run.", completed.size());
        } catch (IOException | JsonSyntaxException exception) {
            AEM.LOGGER.warn("Could not read {} ({}); starting from an empty shared book.",
                    path, exception.toString());
        }
    }

    /** Writes the set if it changed. Cheap enough to call on every completion. */
    public static void save() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !dirty) {
            return;
        }
        List<String> snapshot;
        synchronized (completed) {
            snapshot = List.copyOf(completed);
        }
        try {
            Files.writeString(APWorldPaths.writePath(server, FILE_NAME), GSON.toJson(snapshot));
            dirty = false;
        } catch (IOException exception) {
            AEM.LOGGER.warn("Could not save shared advancements ({}).", exception.toString());
        }
    }

    public static void clear() {
        completed.clear();
        dirty = false;
    }
}
