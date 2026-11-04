package fr.euclesia.mcarchipelago.client.mixin;

import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes vanilla {@code AdvancementWidget}'s private advancement-node field via a generated
 * public accessor, so a compat adapter that mixins into a mod's *subclass* of this vanilla widget
 * (e.g. Paginated Advancements' {@code PaginatedAdvancementWidget}) can read the inherited field —
 * {@code @Shadow} only resolves fields declared directly on the mixin's target class, not ones
 * inherited from a superclass, so a subclass-targeted mixin cannot shadow it directly.
 */
@Mixin(AdvancementWidget.class)
public interface AdvancementWidgetAccessor {
    @Accessor("advancementNode")
    AdvancementNode archipelago_euclesia$getAdvancementNode();

    @Accessor("x")
    int archipelago_euclesia$getX();

    @Accessor("y")
    int archipelago_euclesia$getY();

    // The tile's offset in its tab; final in vanilla, moved by TrackerGrid.
    @Mutable
    @Accessor("x")
    void archipelago_euclesia$setX(int x);

    @Mutable
    @Accessor("y")
    void archipelago_euclesia$setY(int y);
}
