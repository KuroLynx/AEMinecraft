package fr.euclesia.mcarchipelago.server;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.ap.ArchipelagoGameplayListener;
import fr.euclesia.mcarchipelago.server.command.AEMCommandRegistry;
import fr.euclesia.mcarchipelago.server.command.ArchipelagoCommandModule;
import fr.euclesia.mcarchipelago.server.event.MinecraftEventBridge;

public final class AEMServerBridge {
    private static boolean registered;

    private AEMServerBridge() {}

    public static void register() {
        if (registered) {
            return;
        }

        registered = true;

        AEMCommandRegistry.create()
                .module(new ArchipelagoCommandModule())
                .register();

        MinecraftEventBridge.register();
        AEM.ARCHIPELAGO.client().addListener(new ArchipelagoGameplayListener());
    }
}
