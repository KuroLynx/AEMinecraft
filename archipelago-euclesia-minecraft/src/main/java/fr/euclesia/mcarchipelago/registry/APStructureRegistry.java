package fr.euclesia.mcarchipelago.registry;

import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structure spawn lock: a locked structure does not generate until its unlock item is received.
 * Read from worldgen threads (see {@code StructureStartMixin}), so the backing collections are
 * concurrent.
 */
public final class APStructureRegistry {
    private final Map<String, Long> lockItemIds = new ConcurrentHashMap<>();
    private final Set<String> unlocked = ConcurrentHashMap.newKeySet();
    // Bumped whenever the unlocked set changes. Lets the Structure Finder invalidate its cached scan
    // on a structure unlock (which changes which structures are findable) without reacting to every
    // received item — notably, receiving a finder must NOT invalidate it.
    private volatile int unlockVersion;

    public void loadSlotData(APSlotData slotData) {
        lockItemIds.clear();
        lockItemIds.putAll(slotData.structureLocks());
        // Reset unlocks; the full ReceivedItems sent on connect re-applies them.
        unlocked.clear();
        unlockVersion++;
    }

    public boolean isLocked(String structureGameId) {
        return lockItemIds.containsKey(structureGameId) && !unlocked.contains(structureGameId);
    }

    /** Cheap guard so worldgen can skip the per-placement id lookup when nothing is locked at all. */
    public boolean hasLocks() {
        return !lockItemIds.isEmpty();
    }

    /** A counter that changes whenever the unlocked-structure set changes (load or new unlock). */
    public int unlockVersion() {
        return unlockVersion;
    }

    /**
     * Marks every structure gated behind {@code itemId} as unlocked, returning the ones that were not
     * already unlocked so the caller can replay their skipped placements.
     */
    public Set<String> markUnlockedByItem(long itemId) {
        Set<String> newlyUnlocked = new HashSet<>();
        lockItemIds.forEach((structureId, lockItemId) -> {
            if (lockItemId == itemId && unlocked.add(structureId)) {
                newlyUnlocked.add(structureId);
            }
        });
        if (!newlyUnlocked.isEmpty()) {
            unlockVersion++;
        }
        return newlyUnlocked;
    }
}
