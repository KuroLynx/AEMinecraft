package fr.euclesia.mcarchipelago.net;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.registry.APTrackerRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Server side of hold-to-hint. On a dedicated server only the server holds the AP connection, so
 * the client asks and the server acts — same shape as {@link BiomeFinderNet}.
 *
 * <p>The advancement id is resolved against {@link APTrackerRegistry} server-side rather than
 * trusting anything about the item from the client: only an id that is an *active* tracker of
 * {@link APTrackerRegistry#KIND_UNLOCK kind unlock} this seed can trigger a hint.
 */
public final class HintNet {
    private HintNet() {}

    /** Registers the payload type and the server-side handler. Called from mod init, both sides. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay()
                .register(RequestItemHintPayload.TYPE, RequestItemHintPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestItemHintPayload.TYPE,
                (payload, context) -> requestHint(payload.advancementId()));
    }

    private static void requestHint(String advancementId) {
        APTrackerRegistry.Tracker tracker = AEM.ARCHIPELAGO.client().registries().apTrackers().get(advancementId);
        if (tracker == null || !APTrackerRegistry.KIND_UNLOCK.equals(tracker.kind()) || tracker.itemId() == null) {
            return;
        }
        String name = AEM.ARCHIPELAGO.client().registries().apItems().name(tracker.itemId()).orElse(null);
        if (name == null) {
            return;
        }
        AEM.ARCHIPELAGO.gateway().say("!hint " + name);
    }
}
