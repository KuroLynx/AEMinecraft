package fr.euclesia.mcarchipelago.server.command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.ArrayList;
import java.util.List;

public final class AEMCommandRegistry {
    private final List<AEMCommandModule> modules = new ArrayList<>();

    private AEMCommandRegistry() {}

    public static AEMCommandRegistry create() {
        return new AEMCommandRegistry();
    }

    public AEMCommandRegistry module(AEMCommandModule module) {
        modules.add(module);
        return this;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = Commands.literal("aem");
            for (AEMCommandModule module : modules) {
                module.register(root);
            }
            dispatcher.register(root);
        });
    }
}
