package fr.euclesia.mcarchipelago.client.logic;

/**
 * Reachability state of an advancement location, from the perspective of the
 * connected Archipelago slot.
 */
public enum LogicState {
    /** Reachable right now given the items received so far, and not yet checked. Green. */
    IN_LOGIC,
    /** Not reachable yet (blocked by items the player has not received). Red. */
    OUT_OF_LOGIC,
    /** Already checked / the item behind it is collected. Gray. */
    CHECKED,
    /**
     * Checked, but the advancement itself was never earned: another game's release or collect sent
     * the check. It still counts for nothing toward the advancement goal. Blue.
     */
    COLLECTED,
    /** Not reachable in normal logic, but obtainable by glitching the game. Yellow. */
    GLITCHABLE,
    /** Not an Archipelago location, or logic data is unavailable. No badge. */
    UNKNOWN
}
