package fr.euclesia.mcarchipelago.server.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

public interface AEMCommandModule {
    void register(LiteralArgumentBuilder<CommandSourceStack> root);
}
