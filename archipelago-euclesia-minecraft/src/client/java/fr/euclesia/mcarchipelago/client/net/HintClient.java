package fr.euclesia.mcarchipelago.client.net;

import fr.euclesia.mcarchipelago.net.RequestItemHintPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.Identifier;

/**
 * Sends a hold-to-hint request for one advancement tile's item. The server resolves and
 * validates it — see {@code HintNet}. Send-only: no client-side receiver to register.
 */
public final class HintClient {
    private HintClient() {}

    public static void requestHint(Identifier advancementId) {
        if (ClientPlayNetworking.canSend(RequestItemHintPayload.TYPE)) {
            ClientPlayNetworking.send(new RequestItemHintPayload(advancementId.toString()));
        }
    }
}
