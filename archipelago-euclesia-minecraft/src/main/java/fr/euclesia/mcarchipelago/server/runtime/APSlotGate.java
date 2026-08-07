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
 */
public final class APSlotGate {
    private static volatile boolean slotExpected;

    private APSlotGate() {}

    /**
     * Declare that this world has (or will have) an Archipelago slot, so the locks should hold shut
     * until its data arrives. Called as the server starts, from the world's connection file or the
     * server config, and by {@code /aem connect} for a server that started idle.
     */
    public static void expectSlot() {
        slotExpected = true;
    }

    public static void clear() {
        slotExpected = false;
    }

    /** Whether the slot's data is in hand, so the registries can be trusted. */
    public static boolean isReady() {
        return AEM.ARCHIPELAGO.client().state().isConnected();
    }

    /**
     * Whether to treat everything as locked right now: a slot is coming but we cannot yet tell what
     * it wants. This is the fail-closed default every lock consults.
     */
    public static boolean isAwaitingSlot() {
        return slotExpected && !isReady();
    }
}
