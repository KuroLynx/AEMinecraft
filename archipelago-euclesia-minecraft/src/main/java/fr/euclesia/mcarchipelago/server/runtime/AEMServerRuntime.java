package fr.euclesia.mcarchipelago.server.runtime;

import com.mojang.brigadier.Command;
import fr.euclesia.mcarchipelago.AEM;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class AEMServerRuntime {

    private static MinecraftServer server;

    private AEMServerRuntime() {}

    public static void setServer(MinecraftServer server) {
        AEMServerRuntime.server = server;
    }

    public static void clearServer(MinecraftServer server) {
        if(AEMServerRuntime.server == server) {
            AEMServerRuntime.server = null;
        }
    }

    public static MinecraftServer server() {
        return server;
    }

    public static boolean isArchipelagoReady() {
        return AEM.ARCHIPELAGO.client().state().isConnected();
    }

    public static String defaultSlotName(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? player.getGameProfile().name() : source.getTextName();
    }

    public static String entityGameId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
