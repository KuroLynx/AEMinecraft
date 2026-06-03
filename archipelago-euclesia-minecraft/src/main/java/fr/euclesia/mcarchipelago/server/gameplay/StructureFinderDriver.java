package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.gameplay.StructureFinderState.Snapshot;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the Structure-Finder search on the server tick and publishes results to
 * {@link StructureFinderState} for the client to render. The search is heavy (one
 * {@code findNearestMapStructure} per eligible structure), so it recomputes only when the result
 * could actually change: new items received (the finder tier rose, or a structure unlocked), a
 * dimension change, or the player travelled far enough that "nearest" might differ — and
 * movement-driven recomputes are rate-limited.
 *
 * <p>The number of structures published grows with the finder tier (see
 * {@link StructureFinderService#cap}): more copies reveal more of the surrounding structures.
 */
public final class StructureFinderDriver {
    /** Horizontal distance (blocks) a player must travel before a movement-driven recompute. */
    private static final double RECOMPUTE_DISTANCE = 80.0;
    /** Minimum server ticks between movement-driven recomputes (caps cost while travelling). */
    private static final int MOVE_RECOMPUTE_GAP = 40;

    /** Inputs that produced each player's last published snapshot. Server-thread only. */
    private static final Map<UUID, Context> CONTEXTS = new HashMap<>();

    private StructureFinderDriver() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(StructureFinderDriver::onEndTick);
    }

    private static void onEndTick(MinecraftServer server) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            CONTEXTS.clear();
            return;
        }
        StructureFinderState state = StructureFinderState.get();
        int tickCount = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updatePlayer(state, player, tickCount);
        }
    }

    private static void updatePlayer(StructureFinderState state, ServerPlayer player, int tickCount) {
        UUID uuid = player.getUUID();
        int tier = StructureFinderService.tier(player);
        if (tier <= 0) {
            // No finder yet: drop anything left from a previous state.
            if (CONTEXTS.remove(uuid) != null) {
                state.remove(uuid);
            }
            return;
        }

        int version = AEM.ARCHIPELAGO.client().registries().apItems().receivedVersion();
        ResourceKey<Level> dimension = player.level().dimension();
        BlockPos origin = player.blockPosition();

        Context ctx = CONTEXTS.get(uuid);
        boolean recompute =
                ctx == null
                || ctx.tier() != tier
                || ctx.version() != version
                || !dimension.equals(ctx.dimension())
                || (movedFar(ctx.origin(), origin) && tickCount - ctx.computeTick() >= MOVE_RECOMPUTE_GAP);
        if (!recompute) {
            return;
        }

        // One nearest instance per unlocked type, nearest first; the tier decides how many to keep.
        List<FinderTarget> all = StructureFinderService.findAll(player);
        int keep = StructureFinderService.cap(tier, all.size());
        List<FinderTarget> targets = keep >= all.size() ? all : new ArrayList<>(all.subList(0, keep));

        state.putSnapshot(uuid, new Snapshot(tier, targets));
        CONTEXTS.put(uuid, new Context(tier, version, dimension, origin, tickCount));
    }

    private static boolean movedFar(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz >= RECOMPUTE_DISTANCE * RECOMPUTE_DISTANCE;
    }

    private record Context(int tier, int version, ResourceKey<Level> dimension,
                           BlockPos origin, int computeTick) {}
}
