package fr.euclesia.mcarchipelago.protocol;

public enum APItemClassification {
    NORMAL(0),
    PROGRESSION(1),
    USEFUL(2),
    TRAP(4);

    private final int flag;

    APItemClassification(int flag) {
        this.flag = flag;
    }

    public int flag() {
        return flag;
    }

    public static APItemClassification fromFlag(int flag) {
        for (APItemClassification value : values()) {
            if (value.flag == flag) {
                return value;
            }
        }
        return NORMAL;
    }
}
