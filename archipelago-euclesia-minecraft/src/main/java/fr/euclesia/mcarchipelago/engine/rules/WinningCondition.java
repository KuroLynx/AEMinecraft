package fr.euclesia.mcarchipelago.engine.rules;

public enum WinningCondition {
    KILL_ENDER_DRAGON(0),
    KILL_ALL_BOSSES(1);

    private final int slotValue;

    WinningCondition(int slotValue) {
        this.slotValue = slotValue;
    }

    public int slotValue() {
        return slotValue;
    }

    public static WinningCondition fromSlotValue(int slotValue) {
        for (WinningCondition condition : values()) {
            if (condition.slotValue == slotValue) {
                return condition;
            }
        }
        return KILL_ENDER_DRAGON;
    }
}
