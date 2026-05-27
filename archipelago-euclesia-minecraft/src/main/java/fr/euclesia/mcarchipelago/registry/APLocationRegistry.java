package fr.euclesia.mcarchipelago.registry;

import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class APLocationRegistry {
    private final Map<String, Long> idsByGameId = new HashMap<>();
    private final Map<String, Long> idsByName = new HashMap<>();
    private final Set<Long> missing = new HashSet<>();
    private final Set<Long> checked = new HashSet<>();

    public void loadSlotData(APSlotData slotData) {
        idsByGameId.putAll(slotData.locationIdsByGameId());
    }

    public void registerNameToId(Map<String, Long> locationIdsByName) {
        idsByName.putAll(locationIdsByName);
    }

    public void replaceMissing(Collection<Long> locations) {
        missing.clear();
        missing.addAll(locations);
    }

    public void markChecked(Collection<Long> locations) {
        checked.addAll(locations);
        missing.removeAll(locations);
    }

    public Optional<Long> idForGameId(String gameId) {
        return Optional.ofNullable(idsByGameId.get(gameId));
    }

    public Optional<Long> idForName(String locationName) {
        return Optional.ofNullable(idsByName.get(locationName));
    }

    public boolean isChecked(long locationId) {
        return checked.contains(locationId);
    }

    public boolean isMissing(long locationId) {
        return missing.contains(locationId);
    }
}
