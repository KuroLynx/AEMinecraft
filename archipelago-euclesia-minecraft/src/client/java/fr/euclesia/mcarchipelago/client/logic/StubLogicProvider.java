package fr.euclesia.mcarchipelago.client.logic;

import net.minecraft.resources.Identifier;

/**
 * Placeholder provider for Phase 1.
 *
 * <p>It does NOT compute real Archipelago reachability — that is Phase 2. It only
 * produces a stable, varied pattern of states from the advancement id so the
 * glowing-sphere overlay can be seen and positioned in-game. Swap this out via
 * {@link LogicProviders#set(LogicProvider)} once a real reachability source exists.
 */
public final class StubLogicProvider implements LogicProvider {
    @Override
    public LogicState stateFor(Identifier advancementId) {
        // Deterministic per-advancement so the screen looks stable between frames,
        // cycling all four states so every badge colour is visible during Phase 1.
        int bucket = Math.floorMod(advancementId.toString().hashCode(), 4);
        return switch (bucket) {
            case 0 -> LogicState.IN_LOGIC;
            case 1 -> LogicState.OUT_OF_LOGIC;
            case 2 -> LogicState.CHECKED;
            default -> LogicState.GLITCHABLE;
        };
    }
}
