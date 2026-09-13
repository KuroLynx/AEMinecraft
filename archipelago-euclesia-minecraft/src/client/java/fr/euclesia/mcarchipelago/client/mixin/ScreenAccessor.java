package fr.euclesia.mcarchipelago.client.mixin;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets a screen that was closed and is being shown again run its {@code init()} again. Vanilla only
 * initialises a screen the first time it is shown; after that it just repositions what it already
 * built — which, for the advancement screen, means the tiles (and their descriptions) from before.
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Invoker("rebuildWidgets")
    void archipelago_euclesia$rebuildWidgets();
}
