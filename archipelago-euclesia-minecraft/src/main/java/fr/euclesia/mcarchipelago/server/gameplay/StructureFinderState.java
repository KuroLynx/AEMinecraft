package fr.euclesia.mcarchipelago.server.gameplay;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Structure-Finder state, written by the server tick ({@link StructureFinderDriver}) and read
 * by the client renderer. The mod runs an integrated server, so client and server share this JVM and
 * this singleton — no custom network packet is needed (same pattern as the shared
 * {@code AEM.ARCHIPELAGO.client()} registries the client reads directly).
 *
 * <p>Two per-player maps: {@code selection} is the tier-3 chosen structure (an <em>input</em> the
 * client writes), {@code snapshots} is the latest computed result (an <em>output</em> the server
 * writes). Both are keyed by player UUID so the model stays correct even with more than one player.
 */
public final class StructureFinderState {
    private static final StructureFinderState INSTANCE = new StructureFinderState();

    /** The latest computed finder result for a player. */
    public record Snapshot(int tier, String selectedId, List<FinderTarget> targets) {
        public static final Snapshot EMPTY = new Snapshot(0, null, List.of());
    }

    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, String> selection = new ConcurrentHashMap<>();

    private StructureFinderState() {}

    public static StructureFinderState get() {
        return INSTANCE;
    }

    // --- output (server writes, client reads) ---------------------------------------------------

    public void putSnapshot(UUID player, Snapshot snapshot) {
        snapshots.put(player, snapshot);
    }

    /** The latest snapshot for {@code player}, or {@link Snapshot#EMPTY} if none. */
    public Snapshot snapshot(UUID player) {
        return snapshots.getOrDefault(player, Snapshot.EMPTY);
    }

    public void remove(UUID player) {
        snapshots.remove(player);
        selection.remove(player);
    }

    // --- input (client writes the tier-3 selection, server reads it) ----------------------------

    /** Sets (or clears, when {@code structureId} is null) the tier-3 targeted structure. */
    public void setSelection(UUID player, String structureId) {
        if (structureId == null) {
            selection.remove(player);
        } else {
            selection.put(player, structureId);
        }
    }

    public String selection(UUID player) {
        return selection.get(player);
    }
}
