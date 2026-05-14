package fr.euclesia.mcarchipelago.archipelago;

import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

public final class APSessionState {
    private boolean connected;
    private int team = -1;
    private int slot = -1;
    private JsonObject slotData = new JsonObject();
    private final Set<Long> missingLocations = new HashSet<>();
    private final Set<Long> checkedLocations = new HashSet<>();

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
    }

    public Set<Long> missingLocations() {
        return missingLocations;
    }

    public Set<Long> checkedLocations() {
        return checkedLocations;
    }
}
