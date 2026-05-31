package fr.euclesia.mcarchipelago.registry;

import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;
import fr.euclesia.mcarchipelago.protocol.packet.inbound.APNetworkItem;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class APItemRegistry {
    private final Map<Long, String> namesById = new HashMap<>();
    private final Map<String, Long> idsByName = new HashMap<>();
    private final Set<Long> receivedItemIds = new HashSet<>();
    // Received counts per item id. Progressive items (e.g. Material Handling tiers) are sent
    // once per tier, so logic rules need the count, not just a received/not-received flag.
    private final Map<Long, Integer> receivedCountById = new HashMap<>();
    // Bumped whenever received items change. Lets the client logic evaluator (read on the
    // render thread) cheaply detect when its memoized reachability pass is stale.
    private volatile int receivedVersion;

    public void loadSlotData(APSlotData slotData) {
        registerIdToName(slotData.itemNamesById());
    }

    public void registerNameToId(Map<String, Long> itemIdsByName) {
        itemIdsByName.forEach((name, id) -> {
            idsByName.put(name, id);
            namesById.putIfAbsent(id, name);
        });
    }

    public void registerIdToName(Map<Long, String> itemNamesById) {
        itemNamesById.forEach((id, name) -> {
            namesById.put(id, name);
            idsByName.putIfAbsent(name, id);
        });
    }

    public void markReceived(APNetworkItem item) {
        receivedItemIds.add(item.itemId());
        receivedCountById.merge(item.itemId(), 1, Integer::sum);
        receivedVersion++;
    }

    /**
     * Clears all received items. Called when a {@code ReceivedItems} packet arrives with
     * {@code index == 0}, which the server sends as the full inventory (a resync on
     * (re)connect) — counts must be rebuilt from scratch rather than added on top.
     */
    public void resetReceived() {
        receivedItemIds.clear();
        receivedCountById.clear();
        receivedVersion++;
    }

    public Optional<String> name(long itemId) {
        return Optional.ofNullable(namesById.get(itemId));
    }

    public Optional<Long> id(String itemName) {
        return Optional.ofNullable(idsByName.get(itemName));
    }

    public boolean hasReceived(long itemId) {
        return receivedItemIds.contains(itemId);
    }

    /** Number of copies of {@code itemId} received so far. */
    public int receivedCount(long itemId) {
        return receivedCountById.getOrDefault(itemId, 0);
    }

    /** Number of copies received of the item with this name, or 0 if unknown/none. */
    public int receivedCount(String itemName) {
        Long id = idsByName.get(itemName);
        return id == null ? 0 : receivedCount(id);
    }

    /**
     * A counter that changes whenever the received-item set changes. Used by the client
     * logic evaluator to invalidate its cached reachability pass without diffing counts.
     */
    public int receivedVersion() {
        return receivedVersion;
    }
}
