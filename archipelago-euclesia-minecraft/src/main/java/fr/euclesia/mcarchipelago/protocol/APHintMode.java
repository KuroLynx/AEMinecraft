package fr.euclesia.mcarchipelago.protocol;

public enum APHintMode {
    NONE(0),
    IF_UNCHECKED(1),
    FORCED(2);

    private final int code;

    APHintMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
