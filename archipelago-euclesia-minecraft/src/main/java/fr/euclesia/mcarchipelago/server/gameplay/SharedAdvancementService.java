package fr.euclesia.mcarchipelago.server.gameplay;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>Half-finished advancements count too.</b> Sharing only whole completions left every
 * multi-criterion advancement — Adventuring Time, Monsters Hunted, Balanced Diet, most of BACAP's
 * counting goals — quietly unsharable: one player visits thirty biomes, another visits ten, neither
 * finishes, and because nothing ever completes there is nothing to propagate. The run stalls on a
 * goal it has collectively already done. So individual criteria are shared as they are earned, the
 * same way completions are, and the book records both.
 *
 * <p>Both are persisted per world, so a player who logs in tomorrow — or the first player back after
 * a restart — catches up on everything the run has done, part-done included, rather than starting
 * from the empty book their player file remembers.
 */
public final class SharedAdvancementService {
    private static final String FILE_NAME = "shared_advancements.json";
    private static final Gson GSON = new Gson();

    /** Every advancement the RUN has completed, by id, in completion order. */
    private static final Set<String> completed = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * Every criterion the RUN has earned but not yet turned into a completion, by advancement id.
     * A completed advancement needs no entry here — {@link #completed} implies all of them — so this
     * holds exactly the partial progress that would otherwise be stranded in one player's file.
     */
    private static final Map<String, Set<String>> partial = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Set while we are propagating one completion outward. Awarding an advancement to another player
     * makes their own {@code award} fire, which lands back here — without this the first completion
     * would ripple round the player list forever. It also keeps the Archipelago check to one send:
     * the first player through the door reports the location, the copies do not.
     */
    private static boolean propagating;

    /** Set while folding a whole player in, so the book is written once rather than per criterion. */
    private static boolean bulk;

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
        partial.remove(advancementId); // done is done: the criteria are implied from here on
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
     * Record one earned criterion and give it to everyone else — the partial-progress twin of
     * {@link #onCompleted}. Called for criteria that did NOT finish their advancement; the one that
     * does goes through {@code onCompleted}, which shares the whole thing.
     */
    public static boolean onCriterion(ServerPlayer source, AdvancementHolder holder, String criterion) {
        if (propagating) {
            return false; // a copy we just handed out
        }
        String advancementId = holder.id().toString();
        if (!shareable(advancementId) || completed.contains(advancementId)) {
            return false; // not ours to share, or the run already finished it
        }
        Set<String> earned = partial.computeIfAbsent(advancementId,
                key -> Collections.synchronizedSet(new LinkedHashSet<>()));
        if (!earned.add(criterion)) {
            return false; // the run already had this step; another player simply caught up
        }
        dirty = true;
        if (!bulk) {
            save();
        }

        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return true;
        }
        AEMDebug.log("sharedAdvancement criterion '{}' of '{}' earned by {} -> sharing",
                criterion, advancementId, source != null ? source.getGameProfile().name() : "?");
        List<ServerPlayer> finishers = new ArrayList<>();
        propagating = true;
        try {
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other == source) {
                    continue;
                }
                if (other.getAdvancements().award(holder, criterion)
                        && other.getAdvancements().getOrStartProgress(holder).isDone()) {
                    finishers.add(other);
                }
            }
        } finally {
            propagating = false;
        }
        // A player further along than the book — an existing world's progress the sharing never saw —
        // can be finished off by a criterion that merely advanced everyone else. That is the RUN
        // finishing it, so let it take the normal path outside the guard: recorded once, shared to
        // everybody, check sent once.
        if (!finishers.isEmpty()) {
            AdvancementBridge.onCompleted(finishers.get(0), advancementId);
        }
        return true;
    }

    /**
     * Folds a player's own half-finished advancements into the run's book and hands them to everyone
     * else — the partial-progress counterpart of the completion fold in {@code scanPlayer}.
     *
     * <p>Sharing only starts recording when a criterion is <em>earned</em>, so progress banked before
     * that (an existing world, or anything sitting in a player file from before this feature) stays
     * invisible to the run until its owner happens to earn the next step. This is what pulls it in:
     * run on every join, and on demand from {@code /aem advancements sync} for a server that has
     * years of it lying around.
     *
     * @return how many part-steps were new to the run
     */
    public static int foldIn(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return 0;
        }
        int added = 0;
        bulk = true;  // one write at the end, not one per criterion
        try {
            for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
                if (!shareable(holder.id().toString()) || completed.contains(holder.id().toString())) {
                    continue;
                }
                AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
                if (progress.isDone()) {
                    continue;  // completions are folded in by AdvancementBridge.scanPlayer
                }
                for (String criterion : holder.value().criteria().keySet()) {
                    CriterionProgress state = progress.getCriterion(criterion);
                    if (state != null && state.isDone() && onCriterion(player, holder, criterion)) {
                        added++;
                    }
                }
            }
        } finally {
            bulk = false;
        }
        save();
        if (added > 0) {
            AEMDebug.log("sharedAdvancement folded {} part-step(s) in from {}",
                    added, player.getGameProfile().name());
        }
        return added;
    }

    /**
     * Whether an advancement's criteria belong to the run. Recipe unlocks fire constantly and mean
     * nothing; our own tracker tiles are per-player bookkeeping that {@link RootAdvancementService}
     * reconciles, and sharing their criteria would fight it.
     */
    private static boolean shareable(String advancementId) {
        return !advancementId.startsWith("minecraft:recipes/")
                && !advancementId.startsWith(AEM.MOD_ID + ":");
    }

    /**
     * Bring a joining player up to the run's current state, part-done advancements included. Runs
     * inside the propagation guard, so a player catching up on two hundred advancements does not fire
     * two hundred Archipelago checks for locations the run sent long ago.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || (completed.isEmpty() && partial.isEmpty())) {
            return;
        }
        List<String> snapshot;
        synchronized (completed) {
            snapshot = List.copyOf(completed);
        }
        Map<String, List<String>> partialSnapshot = new LinkedHashMap<>();
        synchronized (partial) {
            partial.forEach((id, criteria) -> partialSnapshot.put(id, List.copyOf(criteria)));
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
            int steps = 0;
            for (Map.Entry<String, List<String>> entry : partialSnapshot.entrySet()) {
                AdvancementHolder holder = find(server, entry.getKey());
                if (holder == null) {
                    continue;
                }
                for (String criterion : entry.getValue()) {
                    if (player.getAdvancements().award(holder, criterion)) {
                        steps++;
                    }
                }
            }
            AEMDebug.log("sharedAdvancement caught {} up on {} advancement(s) and {} part-step(s)",
                    player.getGameProfile().name(), granted, steps);
        } finally {
            propagating = false;
        }
        // The catch-up can finish an advancement the book only had partly done; scanPlayer runs right
        // after this on join and folds any such completion back in (recording it and sending the
        // check), so nothing needs doing here.
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

    /**
     * Loads the run's book for this world. Called as the server starts.
     *
     * <p>Reads both shapes of the file: the current object ({@code completed} plus {@code partial}),
     * and the bare array of ids every world written before partial progress existed. An old world
     * therefore keeps its completions and simply starts tracking part-steps from now on.
     */
    public static void load(MinecraftServer server) {
        completed.clear();
        partial.clear();
        dirty = false;
        Path path = APWorldPaths.dir(server).resolve(FILE_NAME);
        if (!Files.exists(path)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path));
            if (root.isJsonArray()) {
                List<String> stored = GSON.fromJson(root, new TypeToken<List<String>>() {}.getType());
                if (stored != null) {
                    completed.addAll(stored);
                }
            } else if (root.isJsonObject()) {
                Book book = GSON.fromJson(root, Book.class);
                if (book != null) {
                    if (book.completed != null) {
                        completed.addAll(book.completed);
                    }
                    if (book.partial != null) {
                        book.partial.forEach((id, criteria) -> partial.put(id,
                                Collections.synchronizedSet(new LinkedHashSet<>(criteria))));
                    }
                }
            }
            AEM.LOGGER.info("Loaded {} shared advancement(s) and {} part-done for this run.",
                    completed.size(), partial.size());
        } catch (IOException | JsonSyntaxException exception) {
            AEM.LOGGER.warn("Could not read {} ({}); starting from an empty shared book.",
                    path, exception.toString());
        }
    }

    /** Writes the book if it changed. Cheap enough to call on every completion. */
    public static void save() {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null || !dirty) {
            return;
        }
        Book book = new Book();
        synchronized (completed) {
            book.completed = List.copyOf(completed);
        }
        book.partial = new LinkedHashMap<>();
        synchronized (partial) {
            partial.forEach((id, criteria) -> book.partial.put(id, List.copyOf(criteria)));
        }
        try {
            Files.writeString(APWorldPaths.writePath(server, FILE_NAME), GSON.toJson(book));
            dirty = false;
        } catch (IOException exception) {
            AEM.LOGGER.warn("Could not save shared advancements ({}).", exception.toString());
        }
    }

    public static void clear() {
        completed.clear();
        partial.clear();
        dirty = false;
    }

    /** On-disk shape: what the run has finished, and how far it has got on what it hasn't. */
    private static final class Book {
        List<String> completed;
        Map<String, List<String>> partial;
    }
}
