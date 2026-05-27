package fr.euclesia.mcarchipelago.server.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.archipelago.APConnectionOptions;
import fr.euclesia.mcarchipelago.archipelago.ArchipelagoClient;
import fr.euclesia.mcarchipelago.server.runtime.AEMServerRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URISyntaxException;

public final class ArchipelagoCommandModule implements AEMCommandModule {
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

        root.then(Commands.literal("status")
                .executes(context -> status(context.getSource())));

        root.then(Commands.literal("say")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> say(
                                context.getSource(),
                                StringArgumentType.getString(context, "message")
                        ))));
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

    private static int status(CommandSourceStack source) {
        ArchipelagoClient client = AEM.ARCHIPELAGO.client();
        String connection = client.state().isConnected()
                ? "connected team=" + client.state().team() + " slot=" + client.state().slot()
                : "not connected";
        source.sendSuccess(() -> Component.literal("Archipelago: " + connection), false);
        return client.state().isConnected() ? 1 : 0;
    }

    private static int say(CommandSourceStack source, String message) {
        if (!AEMServerRuntime.isArchipelagoReady()) {
            source.sendFailure(Component.literal("Archipelago is not connected"));
            return 0;
        }

        AEM.ARCHIPELAGO.gateway().say(message);
        source.sendSuccess(() -> Component.literal("Sent to Archipelago chat"), false);
        return 1;
    }
}
