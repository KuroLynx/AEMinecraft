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

    /**
     * Whether {@code gameId} is an Archipelago check that exists this seed. Backed by the
     * slot-data {@code locations} map, which the apworld now ships active-only (options like
     * challenge_sanity / kill_sanity drop some), so this is the source of truth for "should
     * this advancement appear / be tracked".
     */
    public boolean isActiveLocation(String gameId) {
        return idsByGameId.containsKey(gameId);
    }

    /** Whether any location mapping has been loaded yet (i.e. slot data is available). */
    public boolean hasLocations() {
        return !idsByGameId.isEmpty();
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
