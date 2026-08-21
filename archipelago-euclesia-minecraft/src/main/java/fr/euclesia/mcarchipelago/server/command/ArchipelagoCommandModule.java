package fr.euclesia.mcarchipelago.server.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.APConnectionOptions;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.archipelago.DeathLinkPreference;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnection;
import fr.euclesia.mcarchipelago.server.connect.APWorldConnector;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import fr.euclesia.mcarchipelago.server.runtime.APSlotGate;
import fr.euclesia.mcarchipelago.server.session.PendingChecks;
import fr.euclesia.mcarchipelago.server.service.DeathLinkSetting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.net.URI;
import java.net.URISyntaxException;

public final class ArchipelagoCommandModule implements AEMCommandModule {
    /** Matches the server-start timeout; long enough for a slow room, short enough to answer. */
    private static final long RECONNECT_TIMEOUT_MS = 20_000L;

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("connect")
                .then(Commands.argument("uri", StringArgumentType.string())
                        .executes(context -> connect(
                                context.getSource(),
                                StringArgumentType.getString(context, "uri"),
                                AEMServerRuntime.defaultSlotName(context.getSource()),
                                ""
                        ))
                        .then(Commands.argument("slot", StringArgumentType.string())
                                .executes(context -> connect(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "uri"),
                                        StringArgumentType.getString(context, "slot"),
                                        ""
                                ))
                                .then(Commands.argument("password", StringArgumentType.string())
                                        .executes(context -> connect(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "uri"),
                                                StringArgumentType.getString(context, "slot"),
                                                StringArgumentType.getString(context, "password")
                                        ))))));

        // Reconnecting is an operator action on the whole run (it settles the offline queue with the
        // room), so it takes no arguments: the world already knows its slot, and asking for the
        // address again would only be a way to point a running world at the wrong room.
        root.then(Commands.literal("reconnect")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> reconnect(context.getSource())));

        root.then(Commands.literal("status")
                .executes(context -> status(context.getSource())));

        root.then(Commands.literal("say")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> say(
                                context.getSource(),
                                StringArgumentType.getString(context, "message")
                        ))));

        // DeathLink belongs to the run, not to a player: one slot, everyone in it. So it is an
        // operator switch, and it lives here because this is the JVM holding the Archipelago
        // session — the client screen's toggle does nothing at all on a dedicated server.
        root.then(Commands.literal("deathlink")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> deathLinkStatus(context.getSource()))
                .then(Commands.literal("on")
                        .executes(context -> setDeathLink(context.getSource(), true)))
                .then(Commands.literal("off")
                        .executes(context -> setDeathLink(context.getSource(), false)))
                // The way back out, so an override is not a one-way door for the rest of the run.
                .then(Commands.literal("default")
                        .executes(context -> clearDeathLink(context.getSource()))));
    }

    private static int connect(CommandSourceStack source, String uriText, String slot, String password) {
        URI uri;
        try {
            uri = new URI(uriText);
        } catch (URISyntaxException exception) {
            source.sendFailure(Component.literal("Invalid Archipelago URI: " + uriText));
            return 0;
        }

        APConnectionOptions options = APConnectionOptions.minecraft(slot, password);
        AEM.ARCHIPELAGO.connect(uri, options)
                .thenRun(() -> source.sendSuccess(() -> Component.literal("Connecting to Archipelago as " + slot), false))
                .exceptionally(throwable -> {
                    source.sendFailure(Component.literal("Archipelago connection failed: " + throwable.getMessage()));
                    return null;
                });
        return 1;
    }

    /**
     * Retries this world's own saved connection, and reports what is waiting to go out.
     *
     * <p>The flush itself is not here: {@code APOfflineSyncListener.onConnected} sends the queue once
     * the handshake completes, so the checks go out whether the link came back through this command,
     * a restart, or a connection that recovered on its own. This is the way to ASK for that, and the
     * only feedback it can honestly give at once is whether the attempt started.
     */
    private static int reconnect(CommandSourceStack source) {
        if (AEM.ARCHIPELAGO.client().state().isConnected()) {
            source.sendSuccess(() -> Component.literal("Archipelago is already connected."), false);
            return 1;
        }
        APWorldConnection connection = APWorldConnection.read(
                source.getServer().getWorldPath(LevelResource.ROOT));
        if (connection == null || !connection.hasSlot()) {
            source.sendFailure(Component.literal(
                    "This world has no saved Archipelago slot; use /aem connect <uri> <slot>."));
            return 0;
        }

        int owed = PendingChecks.size();
        source.sendSuccess(() -> Component.literal("Reconnecting to " + connection.address + ":"
                + connection.port + " as " + connection.slot + "..."), true);

        // Off the server thread: connectBlocking waits on the handshake, and a 20-second stall of the
        // tick loop would look exactly like a crash to everyone in the world.
        Thread attempt = new Thread(() -> {
            boolean ok = APWorldConnector.connectBlocking(connection, RECONNECT_TIMEOUT_MS);
            source.getServer().execute(() -> {
                if (ok) {
                    source.sendSuccess(() -> Component.literal("Archipelago reconnected."
                            + (owed > 0 ? " Sending " + owed + " queued check(s)." : "")), true);
                } else {
                    source.sendFailure(Component.literal("Archipelago is still unreachable; "
                            + "still playing offline" + (owed > 0 ? " with " + owed + " check(s) queued." : ".")));
                }
            });
        }, "aem-reconnect");
        attempt.setDaemon(true);
        attempt.start();
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        String connection;
        if (client.state().isConnected()) {
            connection = "connected team=" + client.state().team() + " slot=" + client.state().slot();
        } else if (APSlotGate.isOffline()) {
            // Offline is a working state, not a failure, so name it as one and say what it costs.
            connection = "OFFLINE on cached slot data (no new items until reconnect)";
        } else {
            connection = "not connected";
        }
        String summary = connection;
        source.sendSuccess(() -> Component.literal("Archipelago: " + summary), false);
        if (!PendingChecks.isEmpty()) {
            int owed = PendingChecks.size();
            boolean goal = PendingChecks.goalPending();
            source.sendSuccess(() -> Component.literal("  " + owed + " check(s)"
                    + (goal ? " and the completed goal" : "")
                    + " earned offline, waiting for /aem reconnect"), false);
        }
        return client.state().isConnected() ? 1 : 0;
    }

    /** Reports the current setting and where it came from: the slot's option, or an override. */
    private static int deathLinkStatus(CommandSourceStack source) {
        boolean enabled = DeathLinkPreference.enabled();
        String origin;
        if (!DeathLinkPreference.overridden()) {
            origin = "from the slot's death_link option";
        } else {
            // Distinguish a saved override from one the Archipelago screen set for this session
            // only, so the report is not claiming a restart will keep something it will not.
            Boolean stored = DeathLinkSetting.stored(source.getServer());
            origin = stored != null && stored == enabled
                    ? "saved in this world; /aem deathlink default to undo"
                    : "set for this session only";
        }
        source.sendSuccess(() -> Component.literal(
                "DeathLink: " + (enabled ? "ON" : "OFF") + " (" + origin + ")"), false);
        return enabled ? 1 : 0;
    }

    /**
     * Turns DeathLink on or off for the whole run and saves it in the world, so a restart comes back
     * the way the operator left it rather than the way the YAML asked for.
     *
     * <p>Allowed with no session: the override is stored and honoured when one connects, which is
     * exactly the case where someone wants it off before anybody can die.
     */
    private static int setDeathLink(CommandSourceStack source, boolean enabled) {
        DeathLinkPreference.setEnabled(enabled);
        DeathLinkSetting.save(source.getServer(), enabled);
        // Broadcast: this changes a rule everyone is playing under, so it should not be a quiet edit.
        source.sendSuccess(() -> Component.literal(
                "DeathLink " + (enabled ? "ON" : "OFF") + " for this run (saved)"), true);
        if (!AEM.ARCHIPELAGO.client().state().isConnected()) {
            source.sendSuccess(() -> Component.literal(
                    "  (no live Archipelago session; the tag is sent when one connects)"), false);
        }
        return 1;
    }

    /** Drops the override, saved one included, so the slot's own {@code death_link} decides again. */
    private static int clearDeathLink(CommandSourceStack source) {
        DeathLinkPreference.reset();
        DeathLinkSetting.save(source.getServer(), null);
        // reset() is quiet by design (the client calls it mid-connect), so say it ourselves: this is
        // a live change and the room needs the tag to match it now, not at the next connect.
        DeathLinkPreference.syncTag();
        boolean enabled = DeathLinkPreference.enabled();
        source.sendSuccess(() -> Component.literal(
                "DeathLink follows the slot's death_link again: " + (enabled ? "ON" : "OFF")), true);
        return 1;
    }

    private static int say(CommandSourceStack source, String message) {
        // Chat is one of the few things a cache genuinely cannot stand in for: there is nobody on the
        // other end to hear it. So this asks for a live socket, not merely for trusted slot data.
        if (!AEM.ARCHIPELAGO.client().state().isConnected()) {
            source.sendFailure(Component.literal(APSlotGate.isOffline()
                    ? "Archipelago is offline; /aem reconnect first to send chat."
                    : "Archipelago is not connected"));
            return 0;
        }

        AEM.ARCHIPELAGO.gateway().say(message);
        source.sendSuccess(() -> Component.literal("Sent to Archipelago chat"), false);
        return 1;
    }
}
