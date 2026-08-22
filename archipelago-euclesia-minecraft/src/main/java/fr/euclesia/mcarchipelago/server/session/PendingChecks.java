package fr.euclesia.mcarchipelago.server.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.connect.APWorldPaths;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks earned while the Archipelago link was down, held until it comes back.
 *
 * <p>A check is the one thing an offline run produces that the room genuinely needs. Everything else
 * an offline session does is local — the locks read cached slot data, the advancement book is the
 * world's own — but a completed location is owed to whoever is waiting on the item behind it, and
 * dropping it silently strands another player's progression behind something already earned.
 *
 * <p>So a check that cannot be sent is written here instead, as the resolved Archipelago location id.
 * Resolution works offline because the game-id-to-location-id map lives in the slot data, which
 * {@link APSessionCache} restored; only the sending needs the socket. When a session returns, the
 * whole set goes out as one {@code LocationChecks} packet, which the server treats idempotently — a
 * location it already knows about is simply ignored, so re-sending one costs nothing and a check
 * being here twice is harmless.
 *
 * <p>Persisted per world, because the gap being covered is exactly the one where the server may be
 * restarted (or crash) before the room is reachable again. Only cleared once a send has actually gone
 * out, so a failure to reconnect leaves the queue intact for the next attempt.
 */
public final class PendingChecks {
    private static final String FILE_NAME = "pending_checks.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Insertion-ordered so the room receives them roughly in the order they were earned. */
    private static final Set<Long> QUEUE = new LinkedHashSet<>();
    private static boolean goalReached;
    private static boolean loaded;

    /** The on-disk shape: the queue plus the goal flag. */
    private static final class Stored {
        List<Long> checks = new ArrayList<>();
        boolean goalReached;
    }

    private PendingChecks() {}

    /**
     * Loads the queue for a world. Called at server start, before anything can earn a check, so an
     * offline check made last session is still owed this one.
     */
    public static synchronized void load(Path worldRoot) {
        QUEUE.clear();
        goalReached = false;
        loaded = true;
        Path file = APWorldPaths.readPath(worldRoot, FILE_NAME);
        if (!Files.exists(file)) {
            return;
        }
        try {
            Stored stored = GSON.fromJson(Files.readString(file), Stored.class);
            if (stored != null) {
                if (stored.checks != null) {
                    QUEUE.addAll(stored.checks);
                }
                goalReached = stored.goalReached;
            }
            if (!QUEUE.isEmpty() || goalReached) {
                AEM.LOGGER.info("{} Archipelago check(s){} were made offline and are still owed to the room.",
                        QUEUE.size(), goalReached ? " and the completed goal" : "");
            }
        } catch (IOException | JsonSyntaxException exception) {
            AEM.LOGGER.warn("Could not read the pending Archipelago checks: {}", exception.toString());
        }
    }

    /** Remembers that the run was finished offline, so the goal is reported on reconnect. */
    public static synchronized void markGoalReached() {
        if (!goalReached) {
            goalReached = true;
            save();
        }
    }

    /** Whether a goal completed offline is still owed to the room. */
    public static synchronized boolean goalPending() {
        return goalReached;
    }

    /** Clears the goal flag once it has actually been reported. */
    public static synchronized void clearGoal() {
        if (goalReached) {
            goalReached = false;
            save();
        }
    }

    /** Queues checks that could not be sent, and writes the queue out immediately. */
    public static synchronized void add(Collection<Long> locations) {
        boolean changed = false;
        for (Long id : locations) {
            if (id != null) {
                changed |= QUEUE.add(id);
            }
        }
        if (changed) {
            AEM.LOGGER.info("Offline: {} check(s) queued for the next Archipelago session ({} owed).",
                    locations.size(), QUEUE.size());
            save();
        }
    }

    /** Whether anything at all is waiting to be sent. */
    public static synchronized boolean isEmpty() {
        return QUEUE.isEmpty() && !goalReached;
    }

    /** How many checks are owed to the room. */
    public static synchronized int size() {
        return QUEUE.size();
    }

    /** A snapshot of the queue, for reporting. */
    public static synchronized List<Long> peek() {
        return List.copyOf(QUEUE);
    }

    /**
     * Hands the queue to the caller and empties it. The caller must actually send what it is given:
     * this is the point of no return, so it is only called from the reconnect flush, once the socket
     * is live.
     */
    public static synchronized List<Long> drain() {
        List<Long> drained = new ArrayList<>(QUEUE);
        QUEUE.clear();
        save();
        return drained;
    }

    /** Whether {@link #load} has run, i.e. a world is open and the queue means something. */
    public static synchronized boolean isLoaded() {
        return loaded;
    }

    /** Forgets the queue without sending it. Called as a world closes, not as a way to discard work. */
    public static synchronized void unload() {
        QUEUE.clear();
        goalReached = false;
        loaded = false;
    }

    private static void save() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return;
        }
        try {
            Stored stored = new Stored();
            stored.checks = new ArrayList<>(QUEUE);
            stored.goalReached = goalReached;
            Path file = APWorldPaths.writePath(server, FILE_NAME);
            Files.writeString(file, GSON.toJson(stored));
        } catch (IOException exception) {
            AEM.LOGGER.error("Could not save the pending Archipelago checks — {} check(s) may be lost "
                    + "if the server stops before reconnecting: {}", QUEUE.size(), exception.toString());
        }
    }
}
