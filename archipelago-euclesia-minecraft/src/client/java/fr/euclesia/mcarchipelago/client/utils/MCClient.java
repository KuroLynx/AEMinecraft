package fr.euclesia.mcarchipelago.client.utils;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;

@Environment(EnvType.CLIENT)
public final class MCClient {

    private MCClient() {}

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static void execute(Runnable runnable) { mc().execute(runnable);}

    public static LocalPlayer player() {
        return mc().player;
    }

    public static ClientLevel world() { return mc().level; }

    // The open screen and the toasts. 26.2 moved all three off Minecraft onto Minecraft.gui, so every
    // call in the mod goes through here and this is the one place that knows.
    //? if >=26.2 {
    /*public static Screen screen() { return mc().gui.screen(); }

    public static void setScreen(Screen screen) { mc().gui.setScreen(screen); }

    public static ToastManager toastManager() { return mc().gui.toastManager(); }
    *///?} else {
    public static Screen screen() { return mc().screen; }

    public static void setScreen(Screen screen) { mc().setScreen(screen); }

    public static ToastManager toastManager() { return mc().getToastManager(); }
    //?}

    public static Inventory inventory() {
        return player() != null ? player().getInventory() : null;
    }

}
