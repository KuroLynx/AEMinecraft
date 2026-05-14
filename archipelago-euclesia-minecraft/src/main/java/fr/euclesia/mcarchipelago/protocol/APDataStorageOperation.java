package fr.euclesia.mcarchipelago.protocol;

public enum APDataStorageOperation {
    REPLACE("replace"),
    ADD("add"),
    MUL("mul"),
    MIN("min"),
    MAX("max"),
    AND("and"),
    OR("or"),
    XOR("xor"),
    LEFT_SHIFT("left_shift"),
    RIGHT_SHIFT("right_shift"),
    UPDATE("update");

    private final String id;

    APDataStorageOperation(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
