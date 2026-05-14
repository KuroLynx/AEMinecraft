package fr.euclesia.mcarchipelago.engine.rules;

public enum ResourcesOrder {
    COPPER(1),
    GOLD(2),
    IRON(3),
    DIAMOND(4),
    NETHERITE(5);

    private final int level;

    ResourcesOrder(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
