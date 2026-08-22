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

    /**
     * Whether the slot's data can be trusted — which is what every gameplay service actually needs to
     * know, and is NOT the same as having a socket open.
     *
     * <p>This used to be {@code isConnected()}, and that was safe only because a dropped session
     * kicked everybody out. It stopped being safe the moment a world was allowed to keep running
     * offline: the lock services read this to decide whether they can judge at all, and several of
     * them fail OPEN when the answer is no — {@code DimensionLockService} returns "not blocked",
     * {@code MaterialLockService} returns "no reason". A live-socket definition would therefore have
     * handed an offline player every dimension and every gated material at once.
     *
     * <p>{@link APSlotGate#isReady()} is the honest question: the registries are loaded, from a live
     * session or from this world's cache, which are the same bytes.
     */
    public static boolean isArchipelagoReady() {
        return APSlotGate.isReady();
    }

    public static String defaultSlotName(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? player.getGameProfile().name() : source.getTextName();
    }

    public static String entityGameId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
