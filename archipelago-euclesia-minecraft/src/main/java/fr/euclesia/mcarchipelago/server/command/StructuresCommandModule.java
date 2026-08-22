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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 */
public final class StructuresCommandModule implements AEMCommandModule {
    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("structures")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> report(context.getSource()))
                .then(Commands.literal("release")
                        .executes(context -> release(context.getSource()))));
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
        int queued = StructurePlacementQueue.pending();
        if (held == 0 && queued == 0) {
            source.sendSuccess(() -> Component.literal("No captured structures are being held."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "Captured structure types held: " + lines.size()
                        + (queued > 0 ? " (" + queued + " placements still being written)" : "")), false);
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        source.sendSuccess(() -> Component.literal(
                "Run /aem structures release to place everything marked UNLOCKED."), false);
        return 1;
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
