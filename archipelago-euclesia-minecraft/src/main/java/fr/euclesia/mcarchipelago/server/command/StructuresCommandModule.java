package fr.euclesia.mcarchipelago.server.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.gameplay.StructureCaptureService;
import fr.euclesia.mcarchipelago.server.gameplay.StructurePlacementQueue;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Inspect and recover captured structures.
 *
 * <p>A locked structure is not discarded at worldgen, it is captured and stored in the world save,
 * to be written into the world when its unlock item arrives. Normally nobody needs to think about
 * that. But when something goes wrong in between — an unlock that arrived while the server was
 * stalled, a session that dropped mid-run, a batch that never finished applying — the placements are
 * still sitting in the save with no way to see them or ask for them.
 *
 * <p>{@code /aem structures} lists what is held and whether the slot still considers it locked.
 * {@code /aem structures release} applies everything the slot no longer locks, which is the same
 * thing an unlock item does; it is safe to run at any time and does nothing when there is nothing
 * owed.
 *
 * <p>An unlocked structure is only built where its chunks are loaded, so one nobody has been near yet
 * waits for a player. {@code /aem structures} counts those apart from what is being written, and
 * {@code /aem structures waiting} says where they are, nearest first.
 */
public final class StructuresCommandModule implements AEMCommandModule {
    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("structures")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> report(context.getSource()))
                .then(Commands.literal("release")
                        .executes(context -> release(context.getSource())))
                .then(Commands.literal("waiting")
                        .executes(context -> waiting(context.getSource()))));
    }

    private static int report(CommandSourceStack source) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return 0;
        }
        int held = 0;
        List<String> lines = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (String structureId : StructureCaptureService.capturedStructureIds(level)) {
                boolean locked = AEMServerRuntime.isArchipelagoReady()
                        && AEM.ARCHIPELAGO.client().registries().apStructures().isLocked(structureId);
                lines.add("  " + structureId + " in " + level.dimension().identifier()
                        + (locked ? " (still locked)" : " (UNLOCKED - owed to you)"));
                held++;
            }
        }
        int writing = StructurePlacementQueue.writing();
        List<StructurePlacementQueue.Waiting> waiting = StructurePlacementQueue.waitingForChunks();
        if (held == 0 && writing == 0 && waiting.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No captured structures are being held."), false);
            return 1;
        }
        if (held > 0) {
            source.sendSuccess(() -> Component.literal("Captured structure types held: " + lines.size()), false);
            lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
            source.sendSuccess(() -> Component.literal(
                    "Run /aem structures release to place everything marked UNLOCKED."), false);
        }
        if (writing > 0) {
            source.sendSuccess(() -> Component.literal(
                    writing + " placement(s) being written now; they finish over the next few seconds."), false);
        }
        if (!waiting.isEmpty()) {
            // Unlocked, but built only where a player has loaded the area: not stuck, just not visited.
            Map<String, Integer> byType = new TreeMap<>();
            waiting.forEach(entry -> byType.merge(entry.structureId(), 1, Integer::sum));
            source.sendSuccess(() -> Component.literal(waiting.size()
                    + " unlocked structure(s) wait for a player to come near; each is built when its area loads:"), false);
            byType.forEach((id, count) -> source.sendSuccess(() -> Component.literal("  " + id + " x" + count), false));
            source.sendSuccess(() -> Component.literal("Run /aem structures waiting to see where."), false);
        }
        return 1;
    }

    /** How many of the waiting structures to list: the nearest ones, so a big backlog doesn't flood chat. */
    private static final int WAITING_SHOWN = 10;

    private static int waiting(CommandSourceStack source) {
        List<StructurePlacementQueue.Waiting> waiting = new ArrayList<>(StructurePlacementQueue.waitingForChunks());
        if (waiting.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No unlocked structure is waiting for a player."), false);
            return 1;
        }
        // Nearest first, those in your own dimension before the others.
        Vec3 at = source.getPosition();
        ServerLevel here = source.getLevel();
        waiting.sort(Comparator
                .comparing((StructurePlacementQueue.Waiting entry) -> entry.level() != here)
                .thenComparingDouble(entry -> horizontalDistance(at, entry)));
        source.sendSuccess(() -> Component.literal(waiting.size() + " unlocked structure(s) wait for a player"
                + (waiting.size() > WAITING_SHOWN ? "; the " + WAITING_SHOWN + " nearest:" : ":")), false);
        for (StructurePlacementQueue.Waiting entry : waiting.subList(0, Math.min(WAITING_SHOWN, waiting.size()))) {
            int x = entry.origin().getMinBlockX() + 8;
            int z = entry.origin().getMinBlockZ() + 8;
            String where = entry.level() == here
                    ? (int) horizontalDistance(at, entry) + " blocks away"
                    : "in " + entry.level().dimension().identifier();
            source.sendSuccess(() -> Component.literal(
                    "  " + entry.structureId() + " at x " + x + ", z " + z + " (" + where + ")"), false);
        }
        return 1;
    }

    private static double horizontalDistance(Vec3 at, StructurePlacementQueue.Waiting entry) {
        double dx = entry.origin().getMinBlockX() + 8 - at.x();
        double dz = entry.origin().getMinBlockZ() + 8 - at.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int release(CommandSourceStack source) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return 0;
        }
        if (!AEMServerRuntime.isArchipelagoReady()) {
            source.sendFailure(Component.literal(
                    "Not connected to Archipelago, so there is no way to tell what is still locked."));
            return 0;
        }
        Set<String> release = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (String structureId : StructureCaptureService.capturedStructureIds(level)) {
                if (!AEM.ARCHIPELAGO.client().registries().apStructures().isLocked(structureId)) {
                    release.add(structureId);
                }
            }
        }
        if (release.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "Nothing owed: every held structure is still locked."), false);
            return 1;
        }
        StructureCaptureService.applyUnlocked(server, release);
        source.sendSuccess(() -> Component.literal(
                "Queued " + release.size() + " structure type(s); they will appear over the next few seconds."),
                true);
        return 1;
    }
}
