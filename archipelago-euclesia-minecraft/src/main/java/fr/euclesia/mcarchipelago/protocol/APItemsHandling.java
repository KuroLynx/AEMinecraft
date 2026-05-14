package fr.euclesia.mcarchipelago.protocol;

public enum APItemsHandling {
    LOCAL(1),
    REMOTE(2),
    STARTING(4);

    public static final int ALL = LOCAL.mask | REMOTE.mask | STARTING.mask;

    private final int mask;

    APItemsHandling(int mask) {
        this.mask = mask;
    }

    public int mask() {
        return mask;
    }

    public static int combine(APItemsHandling... values) {
        int result = 0;
        for (APItemsHandling value : values) {
            result |= value.mask;
        }
        return result;
    }
}
