package fr.euclesia.mcarchipelago.server.runtime;

import fr.euclesia.mcarchipelago.AEM;

/**
 * Whether this server knows what its Archipelago slot wants yet — and what to do while it does not.
 *
 * <p>Singleplayer never had to ask. The client connects before the world finishes loading, so by the
 * time anything generates the slot data is already in hand. A dedicated server is asynchronous: the
 * room may be down, the handshake may be slow, an operator may deliberately boot idle and run
 * {@code /aem connect} later. Meanwhile the world generates.
 *
 * <p>That matters because the locks used to fail OPEN — every one of them answered "not locked" when
 * the session was not ready. On a server that quietly destroys the run: spawn chunks generate a
 * mansion the slot meant to hold behind an unlock item, it is placed for real, and no later
 * connection can put it back. The damage is permanent and invisible.
 *
 * <p>So while a slot is expected but unknown, everything is locked instead. Structures are captured
 * rather than placed, mobs do not spawn, gated items cannot be picked up. Nothing is lost: capture is
 * the same mechanism the unlock items already use, so once the slot data arrives
 * {@link fr.euclesia.mcarchipelago.server.gameplay.SlotReleaseService} hands back everything the slot
 * turns out not to lock.
 *
 * <p>The "expected" half matters as much as the "unknown" half. A server with no slot configured at
 * all is not a run waiting to start, it is not an Archipelago server — locking it forever would brick
 * it. So the gate only closes once something has told us a slot is coming.
 *
 * <h2>Known does not mean connected</h2>
 *
 * <p>The question this gate asks is "do we know what the slot wants", and that is not the same as
 * "is the socket up". A world that has connected once has its slot data written down
 * ({@link fr.euclesia.mcarchipelago.server.session.APSessionCache}), and cached slot data answers the
 * locks exactly as well as a live session does — it is the same bytes. So {@link #trustCache()}
 * declares the data known from the cache, and the gate opens on that alone.
 *
 * <p>The link then matters only for exchanging progress: receiving items and sending checks. Checks
 * earned meanwhile queue in {@link fr.euclesia.mcarchipelago.server.session.PendingChecks} and go out
 * on reconnect. What a live session still gives that a cache cannot is INCOMING items, so an offline
 * run can complete checks but cannot unlock anything new — which is a reason to reconnect, not a
 * reason to refuse to play.
 */
public final class APSlotGate {
    private static volatile boolean slotExpected;
    private static volatile boolean cacheTrusted;

    private APSlotGate() {}

    /**
     * Declare that this world has (or will have) an Archipelago slot, so the locks should hold shut
     * until its data arrives. Called as the server starts, from the world's connection file or the
     * server config, and by {@code /aem connect} for a server that started idle.
     */
    public static void expectSlot() {
        slotExpected = true;
    }

    /**
     * Declare the slot data known from this world's cache, so play continues without a session.
     * Called once the cache has actually been restored into the registries — never merely because a
     * file exists, since a gate opened over empty registries is the fail-open bug this class exists
     * to prevent.
     */
    public static void trustCache() {
        cacheTrusted = true;
    }

    public static void clear() {
        slotExpected = false;
        cacheTrusted = false;
    }

    /** Whether the slot's data is in hand, so the registries can be trusted — live or cached. */
    public static boolean isReady() {
        return AEM.ARCHIPELAGO.client().state().isConnected() || cacheTrusted;
    }

    /** Whether the world is running on cached slot data rather than a live session. */
    public static boolean isOffline() {
        return cacheTrusted && !AEM.ARCHIPELAGO.client().state().isConnected();
    }

    /**
     * Whether to treat everything as locked right now: a slot is coming but we cannot yet tell what
     * it wants. This is the fail-closed default every lock consults.
     */
    public static boolean isAwaitingSlot() {
        return slotExpected && !isReady();
    }
}
