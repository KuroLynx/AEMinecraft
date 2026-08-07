package fr.euclesia.mcarchipelago.server;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.ap.ArchipelagoChatListener;
import fr.euclesia.mcarchipelago.server.ap.ArchipelagoConnectionListener;
import fr.euclesia.mcarchipelago.server.ap.ArchipelagoGameplayListener;
import fr.euclesia.mcarchipelago.server.command.AEMCommandRegistry;
import fr.euclesia.mcarchipelago.server.command.ArchipelagoCommandModule;
import fr.euclesia.mcarchipelago.server.command.DumpCommandModule;
import fr.euclesia.mcarchipelago.server.event.MinecraftEventBridge;
import fr.euclesia.mcarchipelago.server.gameplay.StructurePlacementQueue;
import fr.euclesia.mcarchipelago.server.gameplay.TrapMobService;
import fr.euclesia.mcarchipelago.server.gameplay.TrapPlatformService;

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
                .module(new DumpCommandModule())
                .register();

        // Spread unlocked-structure placement across ticks (a mass unlock is otherwise one huge tick).
        StructurePlacementQueue.register();

        // Despawn trap-conjured mobs and tear down MLG-trap platforms after their lifetime.
        TrapMobService.register();
        TrapPlatformService.register();

        MinecraftEventBridge.register();
        AEM.ARCHIPELAGO.client().addListener(new ArchipelagoGameplayListener());
        AEM.ARCHIPELAGO.client().addListener(new ArchipelagoChatListener());
        AEM.ARCHIPELAGO.client().addListener(new ArchipelagoConnectionListener());
    }
}
