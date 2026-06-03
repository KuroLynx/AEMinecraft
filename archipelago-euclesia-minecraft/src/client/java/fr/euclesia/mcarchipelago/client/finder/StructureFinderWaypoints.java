package fr.euclesia.mcarchipelago.client.finder;

import fr.euclesia.mcarchipelago.server.gameplay.FinderTarget;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointStyleAssets;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tier 1 of the Structure Finder: mirrors the server-published targets
 * ({@link StructureFinderState}) into the client's locator bar as one position waypoint per target,
 * so the vanilla {@code LocatorBarRenderer} draws them (direction-only pips — exactly the tier-1
 * capability). Injecting straight into the {@link ClientWaypointManager} bypasses the server's
 * transmit-range gate, so structures show no matter how far away they are.
 */
public final class StructureFinderWaypoints {
    /** Stable per-structure waypoint ids (namespaced so we never touch vanilla player waypoints). */
    private static final Map<String, UUID> WAYPOINT_IDS = new HashMap<>();
    /** Structure ids we currently have on the bar, so stale ones can be untracked. */
    private static final Set<String> tracked = new HashSet<>();
    /** The snapshot last applied; skip work until the server publishes a new one. */
    private static StructureFinderState.Snapshot lastApplied;

    private StructureFinderWaypoints() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(StructureFinderWaypoints::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        LocalPlayer player = client.player;
        if (connection == null || player == null) {
            clearAll(connection);
            return;
        }

        StructureFinderState.Snapshot snapshot = StructureFinderState.get().snapshot(player.getUUID());
        if (snapshot == lastApplied) {
            return;  // nothing new published since the last apply
        }
        lastApplied = snapshot;

        ClientWaypointManager manager = connection.getWaypointManager();
        Set<String> desired = new HashSet<>();
        if (snapshot.tier() >= 1) {
            for (FinderTarget target : snapshot.targets()) {
                desired.add(target.structureId());
                // trackWaypoint puts-or-replaces by id, so re-applying the same target is idempotent.
                manager.trackWaypoint(TrackedWaypoint.setPosition(
                        idFor(target.structureId()), icon(), target.pos()));
            }
        }

        tracked.removeIf(structureId -> {
            if (desired.contains(structureId)) {
                return false;
            }
            manager.untrackWaypoint(TrackedWaypoint.empty(idFor(structureId)));
            return true;
        });
        tracked.addAll(desired);
    }

    private static void clearAll(ClientPacketListener connection) {
        if (connection != null && !tracked.isEmpty()) {
            ClientWaypointManager manager = connection.getWaypointManager();
            for (String structureId : tracked) {
                manager.untrackWaypoint(TrackedWaypoint.empty(idFor(structureId)));
            }
        }
        tracked.clear();
        lastApplied = null;
    }

    private static Waypoint.Icon icon() {
        Waypoint.Icon icon = new Waypoint.Icon();
        icon.style = WaypointStyleAssets.DEFAULT;
        return icon;
    }

    private static UUID idFor(String structureId) {
        return WAYPOINT_IDS.computeIfAbsent(structureId,
                id -> UUID.nameUUIDFromBytes(("aem:finder:" + id).getBytes(StandardCharsets.UTF_8)));
    }
}
