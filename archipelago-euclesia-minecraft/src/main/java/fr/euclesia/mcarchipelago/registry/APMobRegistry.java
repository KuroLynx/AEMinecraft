package fr.euclesia.mcarchipelago.registry;

import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class APMobRegistry {
    private final Map<String, Long> trackedMobLocationIds = new HashMap<>();
    private final Set<String> deathListMobs = new HashSet<>();
    private final Map<String, Long> spawnUnlockItemIds = new HashMap<>();
    private final Set<String> unlockedMobs = new HashSet<>();

    public void loadSlotData(APSlotData slotData) {
        trackedMobLocationIds.clear();
        trackedMobLocationIds.putAll(slotData.trackedMobs());

        deathListMobs.clear();
        deathListMobs.addAll(slotData.deathListMobs());

        spawnUnlockItemIds.clear();
        spawnUnlockItemIds.putAll(slotData.mobSpawnLockMobs());
    }

    public Optional<Long> trackedLocationId(String mobGameId) {
        return Optional.ofNullable(trackedMobLocationIds.get(mobGameId));
    }

    public boolean isTracked(String mobGameId) {
        return trackedMobLocationIds.containsKey(mobGameId);
    }

    public boolean isDeathListMob(String mobGameId) {
        return deathListMobs.contains(mobGameId);
    }

    public boolean isSpawnLocked(String mobGameId) {
        return spawnUnlockItemIds.containsKey(mobGameId) && !unlockedMobs.contains(mobGameId);
    }

    public Optional<Long> unlockItemId(String mobGameId) {
        return Optional.ofNullable(spawnUnlockItemIds.get(mobGameId));
    }

    public void markUnlocked(String mobGameId) {
        unlockedMobs.add(mobGameId);
    }

    public void markUnlockedByItem(long itemId) {
        spawnUnlockItemIds.entrySet().stream()
                .filter(entry -> entry.getValue() == itemId)
                .map(Map.Entry::getKey)
                .forEach(unlockedMobs::add);
    }
}
