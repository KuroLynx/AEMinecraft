package fr.euclesia.mcarchipelago.server.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.euclesia.mcarchipelago.server.gameplay.RootAdvancementService;
import fr.euclesia.mcarchipelago.server.gameplay.SharedAdvancementService;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pools everyone's half-finished advancements into the run's shared book, on demand.
 *
 * <p>Sharing records a criterion when it is <em>earned</em>, so anything banked before that — a world
 * that ran for weeks before this existed, or a player whose file has thirty biomes in Adventuring Time
 * from last month — is invisible to the run until its owner happens to earn one more step. Joining
 * folds a player in automatically, but that only helps players who join afterwards.
 *
 * <p>{@code /aem advancements sync} does it now, for everyone online: each player's part-done work is
 * added to the book and handed to the others, so the run holds the union rather than whatever the
 * last person to log in happened to have. Idempotent — running it twice adds nothing the second time
 * — so it is safe whenever you suspect progress has drifted apart.
 */
public final class AdvancementsCommandModule implements AEMCommandModule {
    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("advancements")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("sync")
                        .executes(context -> sync(context.getSource()))));
    }

    private static int sync(CommandSourceStack source) {
        MinecraftServer server = AEMServerRuntime.server();
        if (server == null) {
            return 0;
        }
        if (!AEMServerRuntime.isArchipelagoReady()) {
            source.sendFailure(Component.literal(
                    "No Archipelago session yet - connect first, or the run has no book to pool into."));
            return 0;
        }
        int players = 0;
        int folded = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            players++;
            folded += SharedAdvancementService.foldIn(player);
        }
        // Whatever just landed can have finished something for somebody, so recount the goal tiles.
        RootAdvancementService.syncProgressToAll();

        int total = folded;
        int seen = players;
        source.sendSuccess(() -> Component.literal(total == 0
                ? "Shared advancements: nothing new from " + seen + " player(s) - already pooled."
                : "Shared advancements: pooled " + total + " part-step(s) from " + seen + " player(s)."),
                true);
        return total;
    }
}
