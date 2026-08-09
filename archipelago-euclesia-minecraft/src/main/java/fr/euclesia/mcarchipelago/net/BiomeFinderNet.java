package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.content.BiomeFinderItem;
import fr.euclesia.mcarchipelago.server.gameplay.BiomeFinderService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The Biome Finder's wire protocol: the screen asks what it can search for, and sends back the pick.
 *
 * <p>Both used to be direct calls into {@link BiomeFinderService} from the client, which only works
 * while the client and the server share a process. On a dedicated server the list came back empty
 * ("No biomes available here") and a pick went nowhere.
 *
 * <p>The same packets are used in singleplayer. The integrated server is a server; routing through it
 * costs a tick and leaves one code path to reason about instead of two.
 */
public final class BiomeFinderNet {
    private BiomeFinderNet() {}

    /** Registers the payload types and the two server-side handlers. Called from mod init, both sides. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(BiomeListPayload.TYPE, BiomeListPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(BiomeListRequestPayload.TYPE, BiomeListRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BiomeSearchPayload.TYPE, BiomeSearchPayload.CODEC);

        // Both handlers run on the server thread, which is where the world may be read.
        ServerPlayNetworking.registerGlobalReceiver(BiomeListRequestPayload.TYPE,
                (payload, context) -> sendList(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(BiomeSearchPayload.TYPE,
                (payload, context) -> search(context.player(), payload.biomeId()));
    }

    /** Answers a list request with the biomes the player's current dimension can generate. */
    private static void sendList(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, BiomeListPayload.TYPE)) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Identifier id : BiomeFinderService.availableBiomes(player.level())) {
            ids.add(id.toString());
        }
        ServerPlayNetworking.send(player, new BiomeListPayload(List.copyOf(ids)));
    }

    /**
     * Runs a pick. The finder check is not ceremony: the search is a worldgen scan out to 6400 blocks,
     * so a client that asks for one without holding the compass is refused before it costs anything.
     */
    private static void search(ServerPlayer player, String biomeId) {
        Identifier id = Identifier.tryParse(biomeId);
        if (id == null || BiomeFinderItem.findFirst(player).isEmpty()) {
            return;
        }
        BiomeFinderService.search(player, id);
    }
}
