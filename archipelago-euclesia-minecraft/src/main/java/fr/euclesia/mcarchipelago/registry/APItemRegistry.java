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
}
