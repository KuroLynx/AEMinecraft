package fr.euclesia.mcarchipelago.client.render;

/** Implemented on vanilla's AdvancementTab by AdvancementTabMixin: is this tab laid out by {@link TrackerGrid}? */
public interface TrackerGridTab {
    boolean archipelago_euclesia$isGrid();

    /** Scrolls the grid to the first tile (top-left first) that {@code match} accepts, if any. */
    void archipelago_euclesia$scrollTo(java.util.function.Predicate<net.minecraft.advancements.AdvancementNode> match);
}
