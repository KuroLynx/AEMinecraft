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
 * <p>Keyed by player UUID so the model stays correct even with more than one player.
 */
public final class StructureFinderState {
    private static final StructureFinderState INSTANCE = new StructureFinderState();

    /** The latest computed finder result for a player: the tier and the structures to show. */
    public record Snapshot(int tier, List<FinderTarget> targets) {
        public static final Snapshot EMPTY = new Snapshot(0, List.of());
    }

    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    private StructureFinderState() {}

    public static StructureFinderState get() {
        return INSTANCE;
    }

    public void putSnapshot(UUID player, Snapshot snapshot) {
        snapshots.put(player, snapshot);
    }

    /** The latest snapshot for {@code player}, or {@link Snapshot#EMPTY} if none. */
    public Snapshot snapshot(UUID player) {
        return snapshots.getOrDefault(player, Snapshot.EMPTY);
    }

    public void remove(UUID player) {
        snapshots.remove(player);
    }
}
