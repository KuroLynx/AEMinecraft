package fr.euclesia.mcarchipelago.engine.rules;

public enum ResourcesOrder {
    STONE(1),
    COPPER(2),
    GOLD(3),
    IRON(4),
    DIAMOND(5),
    NETHERITE(6);

    private final int level;

    ResourcesOrder(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
