package fr.euclesia.mcarchipelago.archipelago;

import fr.euclesia.mcarchipelago.AEM;
import fr.euclesia.mcarchipelago.protocol.APItemsHandling;
import fr.euclesia.mcarchipelago.protocol.packet.outbound.ConnectUpdatePacket;

import java.util.List;

/**
 * Runtime DeathLink on/off, toggleable from the Archipelago screen. Until the player toggles it this
 * session it follows the slot's configured {@code death_link} (slot data); after a toggle the override
 * wins. Toggling also notifies the server with a {@link ConnectUpdatePacket}: adding the
 * {@code "DeathLink"} tag makes the server route DeathLink bounces to this slot, removing it stops
 * them — so the toggle governs both sending (gated here) and receiving (gated by the tag server-side).
 *
 * <p>Shared (not client-only) because the death send-gate runs on the integrated server thread
 * ({@link fr.euclesia.mcarchipelago.server.service.DeathLinkService}); in single player the client and
 * server share this JVM, so a static override is visible to both.
 */
public final class DeathLinkPreference {
    /** {@code null} = follow the slot's configured default; otherwise the session override. */
    private static volatile Boolean override;

    private DeathLinkPreference() {}

    /** Whether DeathLink is active: the session override if the player set one, else the slot default. */
    public static boolean enabled() {
        Boolean current = override;
        if (current != null) {
            return current;
        }
        return AEM.ARCHIPELAGO.client().state().parsedSlotData().deathLink();
    }

    /** Sets the session override and, when connected, tells the server via the DeathLink tag. */
    public static void setEnabled(boolean enabled) {
        override = enabled;
        if (AEM.ARCHIPELAGO.client().state().isConnected()) {
            List<String> tags = enabled ? List.of("DeathLink") : List.of();
            AEM.ARCHIPELAGO.client().send(new ConnectUpdatePacket(tags, APItemsHandling.ALL));
        }
    }

    /** Clears the override so a freshly (re)connected session follows its own slot data again. */
    public static void reset() {
        override = null;
    }
}
