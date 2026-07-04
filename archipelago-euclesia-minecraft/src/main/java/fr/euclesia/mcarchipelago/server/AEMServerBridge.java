package fr.euclesia.mcarchipelago.server;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.server.ap.ArchipelagoChatListener;
import fr.euclesia.mcarchipelago.server.ap.ArchipelagoConnectionListener;
import fr.euclesia.mcarchipelago.server.ap.ArchipelagoGameplayListener;
import fr.euclesia.mcarchipelago.server.command.AEMCommandRegistry;
import fr.euclesia.mcarchipelago.server.command.ArchipelagoCommandModule;
import fr.euclesia.mcarchipelago.server.command.DumpCommandModule;
import fr.euclesia.mcarchipelago.server.event.MinecraftEventBridge;
import fr.euclesia.mcarchipelago.server.gameplay.FillerBuffService;
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

        // Despawn trap-conjured mobs and tear down MLG-trap platforms after their lifetime.
        TrapMobService.register();
        TrapPlatformService.register();
        // Tick down the temporary buffs granted by filler items.
        FillerBuffService.register();

        MinecraftEventBridge.register();
        AEM.ARCHIPELAGO.client().addListener(new ArchipelagoGameplayListener());
        AEM.ARCHIPELAGO.client().addListener(new ArchipelagoChatListener());
        AEM.ARCHIPELAGO.client().addListener(new ArchipelagoConnectionListener());
    }
}
