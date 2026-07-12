package fr.euclesia.mcarchipelago.server.gameplay;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.server.connect.APWorldPaths;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;

/**
 * Applies the Archipelago BlazeandCave's Advancements Pack (BACAP) configuration once per world, the
 * first time the world is loaded with the Archipelago session connected.
 *
 * <p>BACAP hands out item/XP rewards and trophies on advancement completion, which sidesteps the
 * multiworld economy, so by default the mod runs BACAP's own disable functions:
 * <ul>
 *   <li>{@code blazeandcave:config/trophies_off} — always (trophies are never wanted in AP);</li>
 *   <li>{@code blazeandcave:config/exp_rewards_off} + {@code .../item_rewards_off} — only when the
 *       {@code bacap_rewards} option is off (the default).</li>
 * </ul>
 * These BACAP functions persist their state (scoreboard) in the world, so this runs only once: a
 * marker file in the world folder records that it has been applied, and a player who later re-enables
 * rewards through BACAP's own menu is left alone. No-op unless the {@code blazeandcave} option is on
 * (the functions only exist when the BACAP datapack is installed).
 */
public final class BacapConfigService {
    private BacapConfigService() {}

    /** Marker so the BACAP config functions run only on the first connected world load. */
    private static final String APPLIED_FILE = "archipelago_bacap_configured";

    private static final String FN_TROPHIES_OFF = "function blazeandcave:config/trophies_off";
    private static final String FN_EXP_REWARDS_OFF = "function blazeandcave:config/exp_rewards_off";
    private static final String FN_ITEM_REWARDS_OFF = "function blazeandcave:config/item_rewards_off";

    /**
     * Runs the BACAP disable functions once, if needed. Safe to call repeatedly and from either the
     * player-join or connect path (whichever happens after the slot data is known). Must be called on
     * the server thread.
     */
    public static void applyIfNeeded(MinecraftServer server) {
        if (server == null || !AEMServerRuntime.isArchipelagoReady()) {
            return; // Slot data not known yet; retried on connect / next join.
        }
        APSlotData slotData = AEM.ARCHIPELAGO.client().state().parsedSlotData();
        if (!slotData.blazeandcave()) {
            return; // BACAP not in play -> its config functions don't exist.
        }

        if (Files.exists(APWorldPaths.readPath(server, APPLIED_FILE))) {
            return; // Already applied for this world.
        }

        // Mark up front so a second call this session (JOIN then the connect handler) can't re-run.
        if (!writeMarker(server)) {
            return; // Couldn't persist the marker; skip rather than risk re-running every load.
        }

        // createCommandSourceStack() is the server console source (full permission); just mute it.
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        run(server, source, FN_TROPHIES_OFF);
        if (!slotData.bacapRewards()) {
            run(server, source, FN_EXP_REWARDS_OFF);
            run(server, source, FN_ITEM_REWARDS_OFF);
        }
    }

    private static void run(MinecraftServer server, CommandSourceStack source, String command) {
        server.getCommands().performPrefixedCommand(source, command);
    }

    private static boolean writeMarker(MinecraftServer server) {
        try {
            Files.writeString(APWorldPaths.writePath(server, APPLIED_FILE), "");
            return true;
        } catch (Exception exception) {
            AEM.LOGGER.warn("Failed to write {}", APPLIED_FILE, exception);
            return false;
        }
    }
}
