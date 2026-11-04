package fr.euclesia.mcarchipelago.client.mixin;

import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The tab the advancement screen shows, for the tracker search box (TrackerSearch). */
@Mixin(AdvancementsScreen.class)
public interface AdvancementsScreenAccessor {
    @Accessor("selectedTab")
    AdvancementTab archipelago_euclesia$getSelectedTab();
}
