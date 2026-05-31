package fr.euclesia.mcarchipelago.client.logic;

import net.minecraft.resources.Identifier;

/**
 * Holds the active {@link LogicProvider} used by the advancement-screen overlay.
 *
 * <p>Defaults to {@link StubLogicProvider}. Phase 2 calls {@link #set(LogicProvider)}
 * (e.g. from client init once connected) to install the real reachability source.
 */
public final class LogicProviders {
    private static volatile LogicProvider active = new StubLogicProvider();

    private LogicProviders() {}

    public static void set(LogicProvider provider) {
        active = (provider == null) ? new StubLogicProvider() : provider;
    }

    public static LogicProvider get() {
        return active;
    }

    /** Convenience used by the renderer. Never returns {@code null}. */
    public static LogicState stateFor(Identifier advancementId) {
        LogicState state = active.stateFor(advancementId);
        return state == null ? LogicState.UNKNOWN : state;
    }
}
