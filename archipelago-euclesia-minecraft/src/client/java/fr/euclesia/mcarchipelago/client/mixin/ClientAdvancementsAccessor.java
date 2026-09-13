package fr.euclesia.mcarchipelago.client.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The client's own advancement progress, which vanilla only hands to the screen's listener. The
 * overlay needs it by id to tell an advancement the run earned from a check another game collected.
 */
@Mixin(ClientAdvancements.class)
public interface ClientAdvancementsAccessor {
    @Accessor("progress")
    Map<AdvancementHolder, AdvancementProgress> archipelago_euclesia$getProgress();
}
