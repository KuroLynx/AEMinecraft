package fr.euclesia.mcarchipelago.client.logic;

import net.minecraft.resources.Identifier;

/**
 * Answers, for a given Minecraft advancement, whether the matching Archipelago
 * location is currently in logic.
 *
 * <p>This is the seam between the advancement-screen rendering (Phase 1) and the
 * real reachability source (Phase 2). The rendering never computes logic itself;
 * it only asks a {@link LogicProvider}. Phase 2 swaps the implementation registered
 * in {@link LogicProviders} (e.g. a data-driven evaluator fed by the apworld, or a
 * data-storage subscription) without touching the renderer or the mixin.
 */
public interface LogicProvider {
    /**
     * @param advancementId the Minecraft advancement id (e.g. {@code minecraft:adventure/bullseye})
     * @return the reachability state for the matching location, never {@code null}
     */
    LogicState stateFor(Identifier advancementId);
}
