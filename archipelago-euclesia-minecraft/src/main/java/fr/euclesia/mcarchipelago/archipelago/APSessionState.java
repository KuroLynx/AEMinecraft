package fr.euclesia.mcarchipelago.archipelago;

import com.google.gson.JsonObject;
import fr.euclesia.mcarchipelago.archipelago.slot.APSlotData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class APSessionState {
    private boolean connected;
    private int team = -1;
    private int slot = -1;
    private JsonObject slotData = new JsonObject();
    private APSlotData parsedSlotData = APSlotData.empty();
    private final Set<Long> missingLocations = new HashSet<>();
    private final Set<Long> checkedLocations = new HashSet<>();
    // Slot number -> display name (alias), from the Connected packet's players list.
    private final Map<Integer, String> playerNamesBySlot = new HashMap<>();

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public int team() {
        return team;
    }

    public void setTeam(int team) {
        this.team = team;
    }

    public int slot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public JsonObject slotData() {
        return slotData;
    }

    public void setSlotData(JsonObject slotData) {
        this.slotData = slotData;
        this.parsedSlotData = APSlotData.fromJson(slotData);
    }

    public APSlotData parsedSlotData() {
        return parsedSlotData;
    }

    public Set<Long> missingLocations() {
        return missingLocations;
    }

    public Set<Long> checkedLocations() {
        return checkedLocations;
    }

    public Map<Integer, String> playerNamesBySlot() {
        return playerNamesBySlot;
    }

    /** Display name for a slot, or {@code null} if unknown. */
    public String playerName(int slot) {
        return playerNamesBySlot.get(slot);
    }
}
